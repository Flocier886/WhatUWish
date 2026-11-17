package com.flocier.domain.agent.runtime.agent.tool.genui;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组件目录是模型发现合同，也是 GenUiSchema 的 kind 白名单。 */
public final class ListUiComponentsTool implements BaseTool {
    @Override public String name() { return "list_ui_components"; }
    @Override public String description() {
        return "Return the gen UI component catalog (kinds + prop hints) for the current subset. "
                + "Call get_genui_guide first for layout, then this tool before non-trivial emit_ui_tree. Read-only.";
    }
    @Override public Object params() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false);
    }
    @Override public Object execute(Object input, AgentContext context) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("node_shape", "{\"kind\":\"...\",\"props\":{...},\"children\":[...]}");
        fields.put("rules", List.of("Every component prop goes inside props.",
                "children is only for nested nodes, never strings.",
                "Omit nodeId unless you need stable emit_ui_patch targets."));
        fields.put("components", GenUiCatalog.listCatalog());
        String observation = JSON.toJSONString(fields);
        return ToolResultPayload.success(observation, observation, fields);
    }
}
