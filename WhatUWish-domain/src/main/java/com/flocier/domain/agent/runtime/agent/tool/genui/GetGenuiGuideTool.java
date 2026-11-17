package com.flocier.domain.agent.runtime.agent.tool.genui;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import java.util.List;
import java.util.Map;

/** 按需返回完整 GenUI 结构、布局与交互指南。 */
public final class GetGenuiGuideTool implements BaseTool {
    @Override public String name() { return "get_genui_guide"; }
    @Override public String description() {
        return "Return the GenUI guide: wire format, workflow_order, layout, anti-patterns. "
                + "Call before non-trivial emit_ui_tree (dashboards, multi-card UIs). "
                + "Then call list_ui_components. Read-only.";
    }
    @Override public Object params() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false);
    }
    @Override public Object execute(Object input, AgentContext context) {
        Map<String, Object> guide = GenUiGuidePayload.payload();
        return ToolResultPayload.success(JSON.toJSONString(guide), JSON.toJSONString(guide), guide);
    }
}
