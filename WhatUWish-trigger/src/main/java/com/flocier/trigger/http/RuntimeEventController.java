package com.flocier.trigger.http;

import com.flocier.api.dto.AutoAgentRequestDTO;
import com.flocier.api.dto.RuntimeAgentResponseDTO;
import com.flocier.api.response.Response;
import com.flocier.application.agent.execute.AgentExecuteCommand;
import com.flocier.application.agent.file.ConversationFileAccessException;
import com.flocier.application.agent.file.ConversationFileService;
import com.flocier.application.agent.file.ConversationFileSessionService;
import com.flocier.application.agent.runtimeevent.RuntimeAgentCatalog;
import com.flocier.application.agent.runtimeevent.RuntimeAgentDescriptor;
import com.flocier.application.agent.runtimeevent.RuntimeAgentExecutionService;
import com.flocier.application.agent.desktopcontrol.DesktopControlApplicationService;
import com.flocier.domain.agent.runtime.event.DesktopControlPayload;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import com.flocier.application.agent.runtimeevent.RuntimeEventScope;
import com.flocier.application.agent.runtimeevent.RuntimeEventSessionRegistry;
import com.flocier.application.agent.runtimeevent.RuntimeEventSseEmitterSubscription;
import com.flocier.application.agent.runtimeevent.history.RuntimeHistoryQueryService;
import com.flocier.application.agent.runtimeevent.history.dto.RuntimeRunReplayDTO;
import com.flocier.application.agent.runtimeevent.history.dto.RuntimeSessionHistoryDTO;
import com.flocier.application.agent.runtimeevent.history.dto.RuntimeSessionSummaryDTO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.util.UUID;

/** RuntimeEvent v1 的独立 SSE 入口；旧 auto_agent 接口保持原语义。 */
@RestController
@RequestMapping("/api/v1/runtime-events")
@CrossOrigin(origins = "*", allowedHeaders = "*", exposedHeaders = {"X-Run-Id", "X-Request-Id"}, methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class RuntimeEventController {
    @Resource private RuntimeAgentCatalog runtimeAgentCatalog;
    @Resource private RuntimeAgentExecutionService runtimeAgentExecutionService;
    @Resource private RuntimeEventSessionRegistry runtimeEventSessionRegistry;
    @Resource private RuntimeHistoryQueryService runtimeHistoryQueryService;
    @Resource private ConversationFileSessionService conversationFileSessionService;
    @Resource private ConversationFileService conversationFileService;
    @Resource private DesktopControlApplicationService desktopControlApplicationService;

    /**
     * 返回 Runtime Event API 实际可以启动的能力，不读取旧 ai_agent 配置表。
     */
    @GetMapping("agents")
    public Response<List<RuntimeAgentResponseDTO>> listRuntimeAgents() {
        List<RuntimeAgentResponseDTO> agents = runtimeAgentCatalog.listAvailableAgents().stream()
                .map(this::toResponse)
                .toList();
        return Response.<List<RuntimeAgentResponseDTO>>builder()
                .code("0000")
                .info("查询成功")
                .data(agents)
                .build();
    }

    /**
     * 本地单用户历史入口。当前没有登录上下文，但查询仍只按精确 sessionId 读取，
     * 后续接入身份后应在 RuntimeHistoryQueryService 的统一边界补充归属校验。
     */
    @GetMapping("sessions")
    public Response<List<RuntimeSessionSummaryDTO>> listSessions(
            @RequestParam(name = "limit", required = false) Integer limit) {
        return Response.<List<RuntimeSessionSummaryDTO>>builder()
                .code("0000").info("查询成功").data(runtimeHistoryQueryService.listSessions(limit)).build();
    }

    @GetMapping("sessions/{sessionId}")
    public ResponseEntity<Response<RuntimeSessionHistoryDTO>> sessionDetail(
            @PathVariable("sessionId") String sessionId, @RequestParam(name = "limit", required = false) Integer limit) {
        return runtimeHistoryQueryService.findSession(sessionId, limit, true)
                .map(detail -> ResponseEntity.ok(Response.<RuntimeSessionHistoryDTO>builder()
                        .code("0000").info("查询成功").data(detail).build()))
                .orElseGet(() -> notFound("未找到该会话历史"));
    }

    @GetMapping("runs/{runId}")
    public ResponseEntity<Response<RuntimeRunReplayDTO>> runDetail(@PathVariable("runId") String runId) {
        var bare = runtimeHistoryQueryService.findRun(runId);
        if (bare.isEmpty()) return notFound("未找到该运行历史");
        return runtimeHistoryQueryService.findRun(runId, true)
                .map(detail -> ResponseEntity.ok(Response.<RuntimeRunReplayDTO>builder()
                        .code("0000").info("查询成功").data(detail).build()))
                .orElseGet(() -> notFound("未找到该运行历史"));
    }

    @PostMapping(value = "run", produces = "text/event-stream")
    public ResponseBodyEmitter run(@RequestBody AutoAgentRequestDTO request,
                                   HttpServletResponse response) throws Exception {
        // 在写入 SSE 响应前验证目录，避免不支持的 agentId 建立一条永远不会产出事件的连接。
        runtimeAgentCatalog.requireAvailableAgent(request.getAiAgentId());
        conversationFileSessionService.requireForRun(request.getSessionId(),
                request.getFileIds() != null && !request.getFileIds().isEmpty());
        // 请求只携带 fileId；服务端在建立 SSE Run 前固定已授权、仍可用的有序文件快照。
        var selectedFiles = conversationFileService.resolveSelection(request.getSessionId(),
                request.getFileIds());
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        String requestId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        // 浏览器在读取首个 SSE 事件前先建立 Run，避免首事件与前端建档竞争。
        response.setHeader("X-Run-Id", runId);
        response.setHeader("X-Request-Id", requestId);
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        RuntimeEventScope scope = new RuntimeEventScope(request.getSessionId(), runId, requestId, request.getAiAgentId());
        RuntimeEventSseEmitterSubscription.open(emitter, runtimeEventSessionRegistry, scope);
        AgentExecuteCommand command = AgentExecuteCommand.builder()
                .aiAgentId(request.getAiAgentId()).message(request.getMessage()).sessionId(request.getSessionId())
                .maxStep(request.getMaxStep()).requestId(requestId).runId(runId).build();
        command.setSelectedFiles(selectedFiles);
        runtimeAgentExecutionService.dispatch(command);
        return emitter;
    }

    @GetMapping("sessions/{sessionId}/desktop-controls")
    public Response<List<DesktopControlPayload>> desktopControls(@PathVariable("sessionId") String sessionId) {
        conversationFileSessionService.requireForRun(sessionId, false);
        return Response.<List<DesktopControlPayload>>builder().code("0000").info("查询成功")
                .data(desktopControlApplicationService.list(sessionId)).build();
    }

    @PostMapping("sessions/{sessionId}/desktop-controls/{controlId}/complete")
    public Response<DesktopControlPayload> completeDesktopControl(@PathVariable("sessionId") String sessionId,
            @PathVariable("controlId") String controlId) {
        conversationFileSessionService.requireForRun(sessionId, false);
        DesktopControlRecord record = desktopControlApplicationService.complete(sessionId, controlId);
        return Response.<DesktopControlPayload>builder().code("0000").info("操作已完成")
                .data(DesktopControlPayload.from(record)).build();
    }

    @PostMapping(value = "sessions/{sessionId}/desktop-controls/resume", produces = "text/event-stream")
    public ResponseBodyEmitter resumeDesktopControl(@PathVariable("sessionId") String sessionId,
            @RequestBody DesktopResumeRequest request,
            HttpServletResponse response) throws Exception {
        conversationFileSessionService.requireForRun(sessionId, false);
        DesktopControlRecord record = desktopControlApplicationService.requireResume(request.resumeRequestId());
        if (!sessionId.equals(record.getSessionId())) throw new IllegalArgumentException("会话不匹配");
        runtimeAgentCatalog.requireAvailableAgent(record.getAgentId());
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Run-Id", record.getResumeRunId());
        response.setHeader("X-Request-Id", record.getResumeRequestId());
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        RuntimeEventScope scope = new RuntimeEventScope(sessionId, record.getResumeRunId(),
                record.getResumeRequestId(), record.getAgentId());
        RuntimeEventSseEmitterSubscription.open(emitter, runtimeEventSessionRegistry, scope);
        try {
            desktopControlApplicationService.claimResume(sessionId, record.getResumeRequestId());
            runtimeAgentExecutionService.dispatchResume(AgentExecuteCommand.builder()
                    .aiAgentId(record.getAgentId()).sessionId(sessionId).message("")
                    .maxStep(record.getMaxStep()).requestId(record.getResumeRequestId())
                    .runId(record.getResumeRunId()).resumeControlId(record.getControlId()).build());
        } catch (RuntimeException failure) {
            emitter.completeWithError(failure);
            throw failure;
        }
        return emitter;
    }

    public record DesktopResumeRequest(String resumeRequestId) { }

    @ExceptionHandler(ConversationFileAccessException.class)
    public ResponseEntity<Response<Void>> handleFileAccess(ConversationFileAccessException exception) {
        HttpStatus status = exception.getCode().endsWith("FORBIDDEN") ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Response.<Void>builder()
                .code(exception.getCode()).info(exception.getCode()).build());
    }

    private RuntimeAgentResponseDTO toResponse(RuntimeAgentDescriptor agent) {
        return RuntimeAgentResponseDTO.builder()
                .agentId(agent.getAgentId())
                .displayName(agent.getDisplayName())
                .description(agent.getDescription())
                .runtimeKind(agent.getRuntimeKind())
                .available(agent.isAvailable())
                .build();
    }

    private <T> ResponseEntity<Response<T>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Response.<T>builder().code("404").info(message).build());
    }
}
