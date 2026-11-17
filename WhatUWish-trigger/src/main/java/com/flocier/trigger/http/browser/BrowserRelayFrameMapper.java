package com.flocier.trigger.http.browser;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import com.flocier.domain.agent.browser.model.BrowserCommand;
import com.flocier.domain.agent.browser.model.BrowserCommandResult;
import com.flocier.domain.agent.browser.model.BrowserRelayInboundMessage;
import com.flocier.domain.agent.browser.model.BrowserTabMetadata;

import java.util.LinkedHashMap;
import java.util.Map;

/** WebSocket JSON 协议边界：原始扩展帧在此转换为类型化 Relay 消息。 */
public class BrowserRelayFrameMapper {

    /** 将扩展发来的心跳、页面变化或命令回包绑定到已握手的 owner/连接 ID。 */
    public BrowserRelayInboundMessage read(String userId, String connectionId, String payload) {
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(connectionId) || StringUtils.isBlank(payload)) {
            return null;
        }
        final JSONObject frame;
        try {
            frame = JSON.parseObject(payload);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (frame == null) {
            return null;
        }
        String type = frame.getString("type");
        if ("hello".equals(type) || "pong".equals(type) || "ping".equals(type)) {
            return BrowserRelayInboundMessage.heartbeat(userId, connectionId);
        }
        if ("tab.changed".equals(type)) {
            return BrowserRelayInboundMessage.tabChanged(
                    userId,
                    connectionId,
                    new BrowserTabMetadata(frame.getString("url"), frame.getString("title"))
            );
        }
        String id = StringUtils.trimToNull(frame.getString("id"));
        if (id == null) {
            return null;
        }
        BrowserCommandResult result = new BrowserCommandResult(
                id,
                Boolean.TRUE.equals(frame.getBoolean("ok")),
                frame.getString("error"),
                frame.getString("errorCode"),
                frame.getString("page"),
                frame.get("data")
        );
        return new BrowserRelayInboundMessage(
                userId,
                connectionId,
                BrowserRelayInboundMessage.Kind.COMMAND_RESULT,
                result,
                tabMetadata(result.data())
        );
    }

    /** 将类型化领域命令编码成扩展能识别的 RPC 帧。 */
    public String write(BrowserCommand command) {
        JSONObject frame = new JSONObject();
        frame.putAll(command.parameters());
        if (command.rpcId() != null) {
            frame.put("id", command.rpcId());
        }
        frame.put("action", command.action().value());
        if (command.deadlineAt() != null) {
            frame.put("deadlineAt", command.deadlineAt());
        }
        return frame.toJSONString();
    }

    private BrowserTabMetadata tabMetadata(Object data) {
        Map<String, Object> dataMap = asMap(data);
        if (dataMap.isEmpty()) {
            return null;
        }
        Object url = dataMap.get("url");
        Object title = dataMap.get("title");
        if (url == null && title == null) {
            return null;
        }
        return new BrowserTabMetadata(
                url == null ? null : String.valueOf(url),
                title == null ? null : String.valueOf(title)
        );
    }

    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        if (data instanceof String text && text.trim().startsWith("{") && text.trim().endsWith("}")) {
            try {
                JSONObject object = JSON.parseObject(text.trim());
                return object == null ? Map.of() : new LinkedHashMap<>(object);
            } catch (RuntimeException ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }
}
