package com.flocier.domain.agent.runtime.agent.tool.canvas;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.util.Map;

/** 从工具结构化事实生成 Live/Replay 共用的版本化画布展示合同。 */
public final class CanvasPublishPresentation {
    private CanvasPublishPresentation() { }

    public static Map<String, Object> fromStructured(Object output) {
        if (!(output instanceof Map<?, ?> facts)) return null;
        Object id = facts.get("artifactId");
        Object title = facts.get("title");
        Object open = facts.get("openInPanel");
        if (!"canvas.publish.v1".equals(facts.get("presentationKind"))
                || !"1".equals(facts.get("presentationSchemaVersion"))
                || !(id instanceof String artifactId) || !artifactId.matches("[a-fA-F0-9-]{36}")
                || !(title instanceof String name) || name.isBlank() || name.length() > 160
                || !(open instanceof Boolean show)) return null;
        return Map.of("kind", "canvas.publish.v1", "schemaVersion", "1",
                "data", Map.of("artifactId", artifactId, "title", name, "openInPanel", show));
    }

    public static Map<String, Object> fromLedger(String responsePayloadJson) {
        try {
            JSONObject payload = JSON.parseObject(responsePayloadJson);
            if (payload == null || !payload.getBooleanValue("success")) return null;
            return fromStructured(payload.get("result"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
