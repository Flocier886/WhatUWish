package com.flocier.domain.agent.browser.policy;

import com.flocier.domain.agent.browser.model.BrowserAction;
import com.flocier.domain.agent.browser.model.BrowserCommand;
import com.flocier.domain.agent.browser.model.BrowserCommandResult;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Relay 应用服务与 Socket 适配器共用的动作白名单及超时规则。 */
public final class BrowserRelayCommandPolicy {

    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "exec", "navigate", "tabs", "cookies", "screenshot", "close-window",
            "set-file-input", "insert-text", "bind", "network-capture-start",
            "network-capture-read", "wait-download", "cdp", "frames"
    );

    public BrowserCommandResult rejection(BrowserCommand command) {
        if (command == null || command.action() == null) {
            return BrowserCommandResult.invalid(command == null ? null : command.rpcId(),
                    "userId and action are required");
        }
        if (command.userId() == null || command.userId().isBlank()) {
            return BrowserCommandResult.invalid(command.rpcId(), "userId and action are required");
        }
        BrowserAction action = command.action();
        if (action.isLeaseRelease()) {
            return null;
        }
        if (!ALLOWED_ACTIONS.contains(action.value().toLowerCase(Locale.ROOT))) {
            return BrowserCommandResult.unsupported(command.rpcId());
        }
        return null;
    }

    public boolean isLeaseRelease(BrowserCommand command) {
        return command != null && command.action() != null && command.action().isLeaseRelease();
    }

    /** 将请求超时、动作上限和已有截止时间合并，避免 RPC 无限等待扩展回包。 */
    public BrowserCommand withEffectiveTimeout(BrowserCommand command,
                                                Duration defaultTimeout,
                                                long nowMillis) {
        Duration requested = command.timeout() == null
                ? timeoutFromParameters(command, defaultTimeout)
                : command.timeout();
        long timeoutMillis = Math.max(1L, requested == null ? 1L : requested.toMillis());
        timeoutMillis = Math.min(timeoutMillis, actionCapMillis(command));
        if (command.deadlineAt() != null && command.deadlineAt() > nowMillis) {
            timeoutMillis = Math.min(timeoutMillis, command.deadlineAt() - nowMillis);
        }
        return command.withTimeout(Duration.ofMillis(Math.max(1L, timeoutMillis)));
    }

    private long actionCapMillis(BrowserCommand command) {
        String action = command.action() == null ? "" : command.action().value();
        if ("screenshot".equals(action)) {
            return 30_000L;
        }
        if ("wait-download".equals(action)) {
            Object timeoutMs = command.parameters().get("timeoutMs");
            if (timeoutMs instanceof Number number && number.longValue() > 0) {
                return Math.min(Math.max(1L, number.longValue()), 180_000L);
            }
            return 60_000L;
        }
        return 60_000L;
    }

    private Duration timeoutFromParameters(BrowserCommand command, Duration defaultTimeout) {
        if ("wait-download".equals(command.action().value())) {
            Object timeoutMs = command.parameters().get("timeoutMs");
            if (timeoutMs instanceof Number number && number.longValue() > 0) {
                long seconds = Math.min(180L, Math.max(1L, (number.longValue() + 999L) / 1000L));
                return Duration.ofSeconds(seconds);
            }
        }
        return defaultTimeout;
    }
}
