package com.flocier.domain.agent.runtime.agent.tool.canvas;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.file.WorkspaceArtifactRegistrationService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewPolicy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 发布已写入 outputs/ 的 HTML，复用正式 Artifact 交付与幂等登记。 */
public final class CanvasPublishTool implements BaseTool {
    private final RunWorkspaceService workspace;
    private final WorkspaceArtifactRegistrationService registration;

    public CanvasPublishTool(RunWorkspaceService workspace, WorkspaceArtifactRegistrationService registration) {
        this.workspace = workspace;
        this.registration = registration;
    }
    @Override public String name() { return "canvas_publish"; }
    @Override public String description() {
        return "Publish an existing HTML file from the active workspace into the preview panel. Use ONLY when the user explicitly wants HTML / a webpage / printable report, or page-scale layout that cannot be expressed by GenUI components. Charts / KPI / dashboards / multi-card UI -> prefer emit_ui_tree. First write the page with workspace_write, then pass its html_path. html_path is required; this tool does not accept inline HTML.";
    }
    @Override public Object params() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "string", "description", "Canvas title shown to the user."));
        properties.put("html_path", Map.of("type", "string", "description", "Required path to an existing HTML file under the active workspace."));
        properties.put("open_in_panel", Map.of("type", "boolean", "description", "Whether to notify the UI to open the preview panel. Default true."));
        return Map.of("type", "object", "properties", properties, "required", List.of("html_path"), "additionalProperties", false);
    }
    @Override public Object execute(Object input, AgentContext context) {
        try {
            if (!(input instanceof ToolInvokeRequest request)) throw new IllegalArgumentException("工具参数格式错误");
            Map<String, Object> args = request.getArguments() == null ? Map.of() : request.getArguments();
            Object rawPath = args.get("html_path");
            if (!(rawPath instanceof String htmlPath) || htmlPath.isBlank())
                throw new IllegalArgumentException("html_path is required; write the file with workspace_write first");
            Path path = workspace.writable(context, htmlPath);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("html_path not found");
            String filename = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!filename.endsWith(".html") && !filename.endsWith(".htm")) throw new IllegalArgumentException("html_path must be HTML");
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0 || bytes.length > ArtifactPreviewPolicy.HTML_PREVIEW_LIMIT_BYTES
                    || !ArtifactPreviewPolicy.isUtf8Text(bytes))
                throw new IllegalArgumentException("html_path is not a previewable UTF-8 HTML file");
            Map<String, Object> registered = registration.registerCanvasHtml(context, path, request.getToolCallId());
            String title = args.get("title") instanceof String text && !text.isBlank() ? text.trim() : "Canvas";
            boolean open = !Boolean.FALSE.equals(args.get("open_in_panel"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("presentationKind", "canvas.publish.v1");
            result.put("presentationSchemaVersion", "1");
            result.put("title", title);
            result.put("mode", "html");
            result.put("openInPanel", open);
            result.putAll(registered);
            String observation = JSON.toJSONString(result);
            return ToolResultPayload.builder().success(true).status("success").toolResult(observation)
                    .llmObservation(observation).structuredOutput(result)
                    .artifactRefs(List.of(String.valueOf(registered.get("artifactId")))).build();
        } catch (Exception failure) {
            String message = failure instanceof IllegalArgumentException ? failure.getMessage() : "canvas_publish failed: " + failure.getMessage();
            return ToolResultPayload.failure("canvas_publish_failed", message, message);
        }
    }
}
