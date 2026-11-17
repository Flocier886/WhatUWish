package com.flocier.domain.agent.runtime.agent.tool.genui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** 从已确认工具结果投影 GenUI Tree/Patch，供 Live 和 Replay 使用同一合同。 */
public final class GenUiPresentation {
    private GenUiPresentation() { }

    public static Map<String, Object> fromStructured(String toolName, Object output) {
        if (!(output instanceof Map<?, ?> facts)) return null;
        if (!"1".equals(facts.get("presentationSchemaVersion"))) return null;
        if ("emit_ui_tree".equals(toolName) && "genui.tree.v1".equals(facts.get("presentationKind"))) {
            if (!(facts.get("tree") instanceof Map<?, ?> tree)) return null;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tree", tree);
            data.put("canvasId", facts.get("canvasId"));
            return projection("genui.tree.v1", data);
        }
        if ("emit_ui_patch".equals(toolName) && "genui.patch.v1".equals(facts.get("presentationKind"))) {
            if (!(facts.get("patches") instanceof java.util.List<?> patches) || patches.isEmpty()) return null;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("patches", patches);
            data.put("canvasId", facts.get("canvas_id"));
            data.put("seq", facts.get("seq"));
            return projection("genui.patch.v1", data);
        }
        return null;
    }

    public static Map<String, Object> fromLedger(String toolName, String responsePayloadJson) {
        try {
            JSONObject payload = JSON.parseObject(responsePayloadJson);
            if (payload == null || !payload.getBooleanValue("success")) return null;
            return fromStructured(toolName, payload.get("result"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, Object> projection(String kind, Map<String, Object> data) {
        return Map.of("kind", kind, "schemaVersion", "1", "data", data);
    }
}
