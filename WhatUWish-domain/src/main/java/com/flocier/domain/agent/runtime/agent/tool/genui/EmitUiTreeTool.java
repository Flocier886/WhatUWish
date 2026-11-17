package com.flocier.domain.agent.runtime.agent.tool.genui;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对照 master 发布校验后的树；本站由 ToolResult 展示投影承担 ui_tree 消息。 */
public final class EmitUiTreeTool implements BaseTool {
    @Override public String name() { return "emit_ui_tree"; }
    @Override public String description() {
        return "Emit a validated gen UI tree (schemaVersion 1) that renders inline in chat and in the side panel "
                + "with real React components (Chart=ECharts, ThreeJsFrame=procedural or sandboxed scripted 3D, Model3D=glb/gltf). "
                + "PREFERRED path for charts, KPI tiles, dashboards, multi-card layouts, data tables, simple or scripted 3D, "
                + "and teaching labs (ParametricLab / PythagorasLab: drag sliders; "
                + "ConceptDemo / AnimStepLab: playable concept step animation). "
                + "Do NOT use canvas_publish for simple charts/3D shapes — use Chart / ThreeJsFrame instead. "
                + "Before non-trivial trees: get_genui_guide then list_ui_components. "
                + "Args: {tree, optional canvas_id}. tree may be object or JSON string. "
                + "Prefer emit_ui_patch for small updates. "
                + "Interactive actions: set props.action to {type, payload} with snake_case type — "
                + "e.g. {type:'send_message', payload:{content:'Summarize this'}}, "
                + "{type:'open_url', payload:{url:'https://…'}}, "
                + "{type:'submit_form', payload:{formId:'f1'}} (collects named Form fields). "
                + "Legacy bare actionId string is treated as send_message content. "
                + "Form fields need name + enclosing Form; Button/InteractiveButton fire actions on click."
                + "Prioritize building interactive components to allow users active participation and operation."
                + "Add lightweight 2D animation elements to visualize logic and lower comprehension cost.";
    }
    @Override public Object params() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tree", Map.of("description", "GenUI tree envelope or bare root node (object preferred; JSON string accepted)."));
        properties.put("canvas_id", Map.of("type", "string", "description", "Optional canvas id for continuity."));
        return Map.of("type", "object", "properties", properties, "required", List.of("tree"));
    }
    @Override public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> args = input instanceof ToolInvokeRequest request && request.getArguments() != null
                    ? request.getArguments() : Map.of();
            Object tree = args.get("tree");
            if (tree instanceof String json) {
                if (json.isBlank()) throw new IllegalArgumentException("tree string is empty");
                try { tree = JSON.parse(json); }
                catch (Exception failure) { throw new IllegalArgumentException("tree is not valid JSON", failure); }
            }
            Map<String, Object> normalized = GenUiSchema.validateUiTree(tree);
            String canvasId = args.get("canvas_id") instanceof String id && !id.isBlank() ? id.trim() : null;
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("presentationKind", "genui.tree.v1");
            facts.put("presentationSchemaVersion", "1");
            facts.put("tree", normalized);
            facts.put("canvasId", canvasId);
            facts.put("salvaged", Boolean.TRUE.equals(args.get("__salvaged")));
            String observation = "GenUI tree emitted successfully. Prefer emit_ui_patch for small updates."
                    + (canvasId == null ? "" : " canvas_id=" + canvasId);
            return ToolResultPayload.success(observation, observation, facts);
        } catch (Exception failure) {
            return ToolResultPayload.failure("genui_tree_invalid",
                    "emit_ui_tree validation failed: " + failure.getMessage(), failure.getMessage());
        }
    }
}
