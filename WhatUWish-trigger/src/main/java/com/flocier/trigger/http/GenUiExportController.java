package com.flocier.trigger.http;

import com.flocier.application.agent.file.ConversationFileAccessException;
import com.flocier.application.agent.file.ConversationFileSessionService;
import com.flocier.application.agent.runtimeevent.history.RuntimeHistoryQueryService;
import com.flocier.domain.agent.runtime.event.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.genui.GenUiExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** GenUI 当前树的显式文档导出，核对会话和已发布 canvas。 */
@RestController
@RequestMapping("/api/v1/genui/sessions/{sessionId}")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
@RequiredArgsConstructor
public class GenUiExportController {
    private final ConversationFileSessionService sessions;
    private final RuntimeHistoryQueryService history;

    @PostMapping("/export/{format}")
    public ResponseEntity<?> export(@PathVariable("sessionId") String sessionId, @PathVariable("format") String format,
                                    @RequestBody Map<String, Object> body) {
        sessions.requireAccess(sessionId);
        if (body == null || body.get("tree") == null)
            throw new IllegalArgumentException("tree is required");
        String runId = body.get("runId") instanceof String id ? id : "";
        String canvasId = body.get("canvasId") instanceof String id ? id : "";
        var run = history.findRun(runId).orElseThrow(() -> new IllegalArgumentException("run not found"));
        if (!sessionId.equals(run.getSessionId()) || canvasId.isBlank()
                || run.getEvents().stream().noneMatch(event -> {
                    if (!(event.getPayload() instanceof ToolResultPayload result)
                            || !"emit_ui_tree".equals(result.getToolName())
                            || !"SUCCEEDED".equals(result.getStatus()) || result.getPresentation() == null
                            || !"genui.tree.v1".equals(result.getPresentation().get("kind"))) return false;
                    Object data = result.getPresentation().get("data");
                    Object publishedId = data instanceof Map<?, ?> map ? map.get("canvasId") : null;
                    return canvasId.equals(publishedId == null ? result.getToolInvocationId() : publishedId);
                }))
            throw new ConversationFileAccessException("FILE_SESSION_FORBIDDEN");
        String mode = body.get("mode") == null ? "document" : String.valueOf(body.get("mode"));
        if (!"document".equals(mode) && !"deck".equals(mode))
            throw new IllegalArgumentException("mode must be document or deck");
        byte[] bytes;
        MediaType type;
        if ("pdf".equals(format)) {
            bytes = GenUiExportService.exportPdf(body.get("tree"), mode);
            type = MediaType.APPLICATION_PDF;
        } else if ("docx".equals(format)) {
            bytes = GenUiExportService.exportDocx(body.get("tree"), mode);
            type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        } else {
            throw new IllegalArgumentException("format must be pdf or docx");
        }
        String filename = "genui-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "." + format;
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException cause) {
        return ResponseEntity.badRequest().body(Map.of("info", cause.getMessage()));
    }

    @ExceptionHandler(ConversationFileAccessException.class)
    public ResponseEntity<Map<String, String>> forbidden(ConversationFileAccessException cause) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("info", cause.getCode()));
    }
}
