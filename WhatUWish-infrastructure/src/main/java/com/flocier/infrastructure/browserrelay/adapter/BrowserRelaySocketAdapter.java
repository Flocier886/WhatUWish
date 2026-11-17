package com.flocier.infrastructure.browserrelay.adapter;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.flocier.domain.agent.adapter.port.BrowserRelaySocketPort;
import com.flocier.domain.agent.browser.model.BrowserCommand;
import com.flocier.domain.agent.browser.model.BrowserCommandResult;
import com.flocier.domain.agent.browser.model.BrowserConnectionStatus;
import com.flocier.domain.agent.browser.model.BrowserRelayConnection;
import com.flocier.domain.agent.browser.model.BrowserRelayInboundMessage;
import com.flocier.domain.agent.browser.model.BrowserTabMetadata;
import com.flocier.domain.agent.browser.policy.BrowserRelayCommandPolicy;
import com.flocier.domain.agent.browser.policy.BrowserRelayOwnershipPolicy;
import com.flocier.infrastructure.browserrelay.transport.BrowserRelayConnectionRegistry;
import com.flocier.infrastructure.browserrelay.transport.InMemoryBrowserRelayConnectionRegistry;
import com.flocier.types.agent.config.BrowserRelayProperties;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relay 的在线连接与 RPC 等待实现：一个 owner 当前对应一个扩展连接。
 * 负责替换旧连接、以 RPC ID 关联请求和回包、处理断开与超时；动作规则由领域 policy 决定。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "reactor.browser-relay", name = "enabled", havingValue = "true")
public class BrowserRelaySocketAdapter implements BrowserRelaySocketPort {

    private static final long LATE_RESULT_GRACE_SECONDS = 30;

    private final BrowserRelayProperties properties;
    private final BrowserRelayConnectionRegistry registry;
    private final BrowserRelayCommandPolicy commandPolicy = new BrowserRelayCommandPolicy();
    private final BrowserRelayOwnershipPolicy ownershipPolicy = new BrowserRelayOwnershipPolicy();
    private final ConcurrentHashMap<String, TabMeta> tabs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingRpc> pending = new ConcurrentHashMap<>();

    /**
     * 类中有两个构造器，必须显式标注由容器使用的这一个；
     * 否则 Spring 会回落到无参构造并抛 No default constructor found。
     */
    @Autowired
    public BrowserRelaySocketAdapter(BrowserRelayProperties properties) {
        this(properties, new InMemoryBrowserRelayConnectionRegistry());
    }

    BrowserRelaySocketAdapter(BrowserRelayProperties properties,
                              BrowserRelayConnectionRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    /** 同一 owner 新连接上线时替换旧连接，并结束旧连接上尚未完成的 RPC。 */
    @Override
    public void register(BrowserRelayConnection connection) {
        if (!ownershipPolicy.owns(connection == null ? null : connection.userId(), connection)) {
            return;
        }
        BrowserRelayConnection previous = registry.replace(connection);
        if (previous != null && !previous.id().equals(connection.id())) {
            failPending(connection.userId(), previous.id(), "browser relay connection replaced");
            terminate(previous);
        }
        log.info("browser relay online userId={} connectionId={}", connection.userId(), connection.id());
    }

    @Override
    public void unregister(String userId, String connectionId) {
        BrowserRelayConnection removed = registry.remove(userId, connectionId);
        if (removed == null) {
            return;
        }
        tabs.remove(userId);
        failPending(userId, connectionId, "extension disconnected");
        log.info("browser relay offline userId={} connectionId={}", userId, connectionId);
    }

    /** 只接受当前连接的帧；心跳、标签页变化和命令结果分别处理。 */
    @Override
    public void accept(BrowserRelayInboundMessage message) {
        if (!ownershipPolicy.hasIdentity(message)) {
            return;
        }
        BrowserRelayConnection current = registry.current(message.userId());
        if (current == null || !current.id().equals(message.connectionId())) {
            return;
        }
        if (message.kind() == BrowserRelayInboundMessage.Kind.HEARTBEAT) {
            return;
        }
        if (message.kind() == BrowserRelayInboundMessage.Kind.TAB_CHANGED) {
            updateTab(message.userId(), message.tabMetadata());
            return;
        }
        BrowserCommandResult result = message.commandResult();
        if (result == null || StringUtils.isBlank(result.rpcId())) {
            return;
        }
        PendingRpc rpc = pending.get(result.rpcId());
        if (rpc == null || !ownershipPolicy.matches(rpc.userId, rpc.connectionId, message)) {
            return;
        }
        if (pending.remove(result.rpcId(), rpc)) {
            BrowserTabMetadata metadata = message.tabMetadata();
            if (metadata == null) {
                metadata = tabMetadataFromData(result.data());
            }
            updateTab(message.userId(), metadata);
            rpc.future.complete(result);
        }
    }

    @Override
    public boolean isOnline(String userId) {
        BrowserRelayConnection connection = registry.current(userId);
        return connection != null && connection.isOpen();
    }

    @Override
    public BrowserConnectionStatus status(String userId) {
        TabMeta meta = userId == null ? null : tabs.get(userId);
        return new BrowserConnectionStatus(
                isOnline(userId),
                meta == null ? null : meta.url,
                meta == null ? null : meta.title
        );
    }

    /** 将领域命令发往扩展并等待匹配回包；超时返回“结果未知”而非假定命令未执行。 */
    @Override
    public BrowserCommandResult execute(BrowserCommand command) {
        if (command == null) {
            return BrowserCommandResult.invalid(null, "userId and action are required");
        }
        if (commandPolicy.isLeaseRelease(command)) {
            return BrowserCommandResult.leaseReleased(command.rpcId());
        }
        if (!isOnline(command.userId())) {
            return BrowserCommandResult.offline(command.rpcId());
        }
        BrowserCommandResult rejection = commandPolicy.rejection(command);
        if (rejection != null) {
            return rejection;
        }
        BrowserCommand effective = commandPolicy.withEffectiveTimeout(
                command,
                Duration.ofSeconds(Math.max(1, properties.getRpcTimeoutSeconds())),
                System.currentTimeMillis()
        );
        String id = resolveRpcId(effective);
        String connectionId = currentConnectionId(effective.userId());
        if (connectionId == null) {
            return BrowserCommandResult.offline(id);
        }
        PendingRpc rpc = new PendingRpc(effective.userId(), connectionId, new CompletableFuture<>());
        if (pending.putIfAbsent(id, rpc) != null) {
            return BrowserCommandResult.duplicate(id);
        }
        long now = System.currentTimeMillis();
        long deadlineAt = now + Math.max(1L, effective.timeout().toMillis());
        if (effective.deadlineAt() != null) {
            deadlineAt = Math.min(deadlineAt, effective.deadlineAt());
        }
        BrowserCommand outbound = effective.withRpcId(id)
                .withDeadlineAt(Math.max(now + 1L, deadlineAt));
        try {
            BrowserRelayConnection connection = registry.current(effective.userId());
            if (connection == null || !connection.isOpen() || !connection.id().equals(rpc.connectionId)) {
                pending.remove(id, rpc);
                return BrowserCommandResult.offline(id);
            }
            synchronized (connection) {
                connection.send(outbound);
            }
            return rpc.future.orTimeout(effective.timeout().toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (Exception exception) {
            Throwable cause = rootCause(exception);
            if (cause instanceof TimeoutException) {
                CompletableFuture.delayedExecutor(LATE_RESULT_GRACE_SECONDS, TimeUnit.SECONDS)
                        .execute(() -> pending.remove(id, rpc));
                return BrowserCommandResult.unknown(id);
            }
            pending.remove(id, rpc);
            return BrowserCommandResult.rpcFailed(id, cause.getMessage());
        }
    }

    @Override
    public void disconnect(String userId) {
        if (StringUtils.isBlank(userId)) {
            return;
        }
        BrowserRelayConnection connection = registry.current(userId);
        if (connection == null) {
            return;
        }
        BrowserRelayConnection removed = registry.remove(userId, connection.id());
        tabs.remove(userId);
        failPending(userId, connection.id(), "disconnected");
        if (removed != null) {
            terminate(removed);
        }
    }

    private String currentConnectionId(String userId) {
        BrowserRelayConnection connection = registry.current(userId);
        return connection == null ? null : connection.id();
    }

    private String resolveRpcId(BrowserCommand command) {
        return StringUtils.isNotBlank(command.rpcId())
                ? command.rpcId()
                : "rpc-" + UUID.randomUUID();
    }

    /** 连接被替换或断开时，只结束属于该连接的未完成请求。 */
    private void failPending(String userId, String connectionId, String message) {
        pending.entrySet().removeIf(entry -> {
            PendingRpc rpc = entry.getValue();
            if (!userId.equals(rpc.userId) || !connectionId.equals(rpc.connectionId)) {
                return false;
            }
            rpc.future.complete(BrowserCommandResult.failure(entry.getKey(), "browser_offline", message));
            return true;
        });
    }

    private void updateTab(String userId, BrowserTabMetadata metadata) {
        if (StringUtils.isBlank(userId) || metadata == null) {
            return;
        }
        TabMeta previous = tabs.get(userId);
        tabs.put(userId, new TabMeta(
                StringUtils.defaultIfBlank(metadata.url(), previous == null ? null : previous.url),
                StringUtils.defaultIfBlank(metadata.title(), previous == null ? null : previous.title)
        ));
    }

    private BrowserTabMetadata tabMetadataFromData(Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            return null;
        }
        Object url = map.get("url");
        Object title = map.get("title");
        if (url == null && title == null) {
            return null;
        }
        return new BrowserTabMetadata(
                url == null ? null : String.valueOf(url),
                title == null ? null : String.valueOf(title)
        );
    }

    private void terminate(BrowserRelayConnection connection) {
        try {
            if (connection.isOpen()) {
                connection.terminate();
            }
        } catch (Exception ignored) {
            // 连接表已经完成替换或断开，关闭旧 socket 失败不改变当前在线状态。
        }
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable cause = exception;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private record TabMeta(String url, String title) {
    }

    private record PendingRpc(String userId, String connectionId, CompletableFuture<BrowserCommandResult> future) {
    }
}
