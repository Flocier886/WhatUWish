package com.flocier.trigger.http.browser;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import com.flocier.domain.agent.browser.BrowserRelayApplicationService;
import com.flocier.domain.agent.browser.model.BrowserCommand;
import com.flocier.domain.agent.browser.model.BrowserCommandResult;
import com.flocier.domain.agent.browser.model.BrowserConnectionStatus;
import com.flocier.domain.agent.browser.model.BrowserRelayConnection;
import com.flocier.domain.agent.browser.model.BrowserRelayInboundMessage;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Spring WebSocket 边界：把扩展连接和文本帧交给 Relay 应用服务。
 * 不在这里保存配对凭据或判断命令权限。
 */
@Component
@ConditionalOnProperty(prefix = "reactor.browser-relay", name = "enabled", havingValue = "true")
public class BrowserRelayHub {

    private final BrowserRelayApplicationService applicationService;
    private final BrowserRelayFrameMapper frameMapper;

    /**
     * 类中有两个构造器，必须显式标注由容器使用的这一个；
     * 否则 Spring 会回落到无参构造并抛 No default constructor found。
     */
    @Autowired
    public BrowserRelayHub(BrowserRelayApplicationService applicationService) {
        this(applicationService, new BrowserRelayFrameMapper());
    }

    BrowserRelayHub(BrowserRelayApplicationService applicationService,
                    BrowserRelayFrameMapper frameMapper) {
        this.applicationService = applicationService;
        this.frameMapper = frameMapper;
    }

    /** 握手确认 owner 后，把 WebSocket 包成领域可用的连接。 */
    public void register(String userId, WebSocketSession session) {
        if (StringUtils.isBlank(userId) || session == null) {
            return;
        }
        applicationService.registerConnection(userId, new WebSocketRelayConnection(userId, session, frameMapper));
    }

    public void unregister(WebSocketSession session) {
        if (session == null) {
            return;
        }
        applicationService.unregisterConnection(userIdOf(session), session.getId());
    }

    /** 把扩展帧解析成心跳、标签页状态或 RPC 结果后交给应用服务。 */
    public void onText(WebSocketSession session, String payload) {
        if (session == null) {
            return;
        }
        String userId = userIdOf(session);
        BrowserRelayInboundMessage message = frameMapper.read(userId, session.getId(), payload);
        if (message != null) {
            applicationService.accept(message);
        }
    }

    public boolean isOnline(String userId) {
        return applicationService.status(userId).isConnected();
    }

    public BrowserConnectionStatus status(String userId) {
        return applicationService.status(userId);
    }

    /** 兼容旧调用入口；内部 HTTP RPC 目前直接调用应用服务。 */
    public BrowserCommandResult call(String userId,
                                     String action,
                                     Map<String, Object> params,
                                     Duration timeout) {
        return applicationService.execute(BrowserCommand.of(userId, action, params, timeout));
    }

    public void disconnect(String userId) {
        applicationService.disconnect(userId);
    }

    private static String userIdOf(WebSocketSession session) {
        if (session.getAttributes() == null) {
            return null;
        }
        Object value = session.getAttributes().get("userId");
        return value == null ? null : String.valueOf(value);
    }

    private record WebSocketRelayConnection(String userId,
                                            WebSocketSession session,
                                            BrowserRelayFrameMapper frameMapper)
            implements BrowserRelayConnection {

        @Override
        public String id() {
            return session.getId();
        }

        @Override
        public boolean isOpen() {
            return session.isOpen();
        }

        @Override
        public void send(BrowserCommand command) throws IOException {
            session.sendMessage(new TextMessage(frameMapper.write(command)));
        }

        @Override
        public void terminate() throws IOException {
            session.close();
        }
    }
}
