package com.flocier.domain.agent.runtime.agent.tool.genui;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 补丁只记录增量事实，由前端按 Run 中的顺序应用到既有树。 */
public final class EmitUiPatchTool implements BaseTool {
    @Override public String name() { return "emit_ui_patch"; }
    @Override public String description() {
        return "Apply incremental JSON-Patch updates (add/replace/remove) to an already-emitted gen UI tree. "
                + "Args: {patches, optional seq, optional canvas_id}. Prefer over re-emitting full tree.";
    }
    @Override public Object params() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("patches", Map.of("type", "array", "minItems", 1,
                "description", "JSON-Patch ops targeting /root/... paths."));
        properties.put("canvas_id", Map.of("type", "string"));
        properties.put("seq", Map.of("type", "integer", "minimum", 0));
        return Map.of("type", "object", "properties", properties, "required", List.of("patches"));
    }
    @Override public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> args = input instanceof ToolInvokeRequest request && request.getArguments() != null
                    ? request.getArguments() : Map.of();
            Map<String, Object> normalized = GenUiSchema.validateUiPatch(args);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("presentationKind", "genui.patch.v1");
            facts.put("presentationSchemaVersion", "1");
            facts.putAll(normalized);
            String observation = "GenUI patch applied (" + ((List<?>) normalized.get("patches")).size() + " ops).";
            return ToolResultPayload.success(observation, observation, facts);
        } catch (Exception failure) {
            return ToolResultPayload.failure("genui_patch_invalid",
                    "emit_ui_patch validation failed: " + failure.getMessage(), failure.getMessage());
        }
    }
}
