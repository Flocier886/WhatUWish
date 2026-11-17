package com.flocier.domain.agent.runtime.agent.tool.canvas;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import java.util.List;
import java.util.Map;

/** 按需返回完整 HTML Canvas 指南，不修改工作区。 */
public final class GetHtmlCanvasGuideTool implements BaseTool {
    @Override public String name() { return "get_html_canvas_guide"; }
    @Override public String description() {
        return "Return the on-demand professional HTML guide for canvas_publish: design method, visual quality, accessibility, preview runtime for an existing workspace HTML file, and delivery. Charts/KPI/dashboards -> emit_ui_tree instead. Read-only, no side effects.";
    }
    @Override public Object params() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false);
    }
    @Override public Object execute(Object input, AgentContext context) {
        Map<String, Object> guide = HtmlCanvasGuidePayload.payload();
        return ToolResultPayload.success(JSON.toJSONString(guide), JSON.toJSONString(guide), guide);
    }
}
