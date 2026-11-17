package com.flocier.domain.agent.adapter.port;

import com.flocier.domain.agent.browser.model.BrowserCommand;
import com.flocier.domain.agent.browser.model.BrowserCommandResult;
import com.flocier.domain.agent.browser.model.BrowserConnectionStatus;
import com.flocier.domain.agent.browser.model.BrowserRelayConnection;
import com.flocier.domain.agent.browser.model.BrowserRelayInboundMessage;

/**
 * Relay 应用服务到在线连接的 Port。
 * 这里仅交换类型化命令与消息；WebSocketSession 和原始 JSON 留在 Trigger 协议层。
 */
public interface BrowserRelaySocketPort {

    void register(BrowserRelayConnection connection);

    void unregister(String userId, String connectionId);

    void accept(BrowserRelayInboundMessage message);

    boolean isOnline(String userId);

    BrowserConnectionStatus status(String userId);

    /** 把命令发给当前连接的扩展，并等待具有相同 RPC ID 的结果。 */
    BrowserCommandResult execute(BrowserCommand command);

    void disconnect(String userId);
}
