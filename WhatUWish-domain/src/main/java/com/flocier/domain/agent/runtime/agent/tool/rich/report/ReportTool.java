package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.rich.RichTool;
import com.flocier.domain.agent.runtime.event.ReportContentDeltaPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * Java 侧 Report Rich Tool。
 *
 * <p>该工具负责建立同一条 Tool Invocation、完成输入 Artifact 授权、消费 Python 私有 SSE，
 * 并将稳定阶段、正式 Artifact 与 ReAct observation 收口到各自的运行时服务中。</p>
 */
@Slf4j
public class ReportTool implements RichTool {

    public static final String TOOL_TYPE = "rich_report";
    private static final int OBSERVATION_LIMIT = 2400;
    private static final int MAX_LIVE_DELTA_CHUNKS = 200;
    private static final int MAX_LIVE_DELTA_CHARS = 48_000;

    private final String name;
    private final String description;
    private final Object params;
    private final String pythonService;
    private final String pythonEndpoint;
    private final ReportStreamClient streamClient;
    private final ReportInputArtifactCandidateResolver inputArtifactCandidateResolver;
    private final ReportInputArtifactResolver inputArtifactResolver;
    private final LedgerFactService ledgerFactService;
    private final ReportRuntimeFactService runtimeFactService;
    private final RuntimeEventPublisher runtimeEventPublisher;

    public ReportTool(
            String name,
            String description,
            Object params,
            String pythonService,
            String pythonEndpoint,
            ReportStreamClient streamClient,
            ReportInputArtifactCandidateResolver inputArtifactCandidateResolver,
            ReportInputArtifactResolver inputArtifactResolver,
            LedgerFactService ledgerFactService,
            ReportRuntimeFactService runtimeFactService,
            RuntimeEventPublisher runtimeEventPublisher
    ) {
        this.name = name;
        this.description = description;
        this.params = params;
        this.pythonService = pythonService;
        this.pythonEndpoint = pythonEndpoint;
        this.streamClient = streamClient;
        this.inputArtifactCandidateResolver = inputArtifactCandidateResolver;
        this.inputArtifactResolver = inputArtifactResolver;
        this.ledgerFactService = ledgerFactService;
        this.runtimeFactService = runtimeFactService;
        this.runtimeEventPublisher = runtimeEventPublisher;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Object params() {
        return params;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request)) {
            return failed(null, Instant.now(), "report_request_invalid", "report_tool 只接受受控工具调用请求。");
        }
        Instant startedAt = Instant.now();
        return executeRich(request, context,
                ledgerFactService.startToolInvocation(context, request, name, TOOL_TYPE), startedAt);
    }

    @Override
    public String richToolType() {
        return TOOL_TYPE;
    }

    /** 兼容既有 Rich Tool 定向测试与非批次调用入口。 */
    public ToolExecutionResult executeRich(ToolInvokeRequest request, AgentContext context) {
        return execute(request, context) instanceof ToolExecutionResult result ? result : failed(request, Instant.now(),
                "report_execution_error", "Report 未返回受控执行结果。");
    }

    @Override
    public ToolExecutionResult executeRich(ToolInvokeRequest request,
                                           AgentContext context,
                                           ToolInvocationLedgerEntity invocation,
                                           Instant startedAt) {
        ToolExecutionResult executionResult;
        boolean runtimeStarted = false;
        try {
            ReportStartRequest startRequest = ReportStartRequest.from(request, invocation.getToolInvocationId());
            if (startRequest.getInputArtifactIds().isEmpty()) {
                startRequest = startRequest.withInputArtifactIds(
                        inputArtifactCandidateResolver.resolveCurrentResearchArtifactIds(context)
                );
            }
            List<ReportInputArtifactDescriptor> inputArtifacts = inputArtifactResolver.resolve(context, startRequest);
            startRequest = startRequest.withResolvedInputs(inputArtifacts);
            runtimeFactService.start(invocation, startRequest);
            runtimeStarted = true;

            AtomicReference<String> registeredArtifactId = new AtomicReference<>();
            LiveDeltaBudget liveDeltaBudget = new LiveDeltaBudget();
            ReportFinalResult result = streamClient.stream(
                    pythonService,
                    pythonEndpoint,
                    startRequest,
                    event -> {
                        ReportRuntimeFactService.EventHandlingResult handled = runtimeFactService.accept(invocation, context, event);
                        if (!handled.isDuplicate() && handled.getFact() != null
                                && StringUtils.hasText(handled.getFact().getArtifactId())) {
                            registeredArtifactId.set(handled.getFact().getArtifactId());
                        }
                    },
                    delta -> publishLiveDelta(invocation, context, delta, liveDeltaBudget)
            );
            executionResult = completed(request, startedAt, result, registeredArtifactId.get(), inputArtifacts.size());
            runtimeFactService.finish(
                    invocation,
                    result,
                    executionResult.isSuccess(),
                    executionResult.getErrorCode(),
                    executionResult.getErrorMessage()
            );
        } catch (ReportStreamException exception) {
            executionResult = failed(request, startedAt, exception.getErrorCode(), exception.getMessage());
            if (runtimeStarted) {
                runtimeFactService.finish(invocation, null, false, exception.getErrorCode(), executionResult.getErrorMessage());
            }
        } catch (RuntimeException exception) {
            log.error("report rich tool failed, toolCallId={}, requestId={}", request.getToolCallId(), request.getRequestId(), exception);
            executionResult = failed(
                    request,
                    startedAt,
                    "report_execution_error",
                    "Report 在获得可信终态前执行失败。"
            );
            if (runtimeStarted) {
                runtimeFactService.finish(
                        invocation,
                        null,
                        false,
                        executionResult.getErrorCode(),
                        executionResult.getErrorMessage()
                );
            }
        }
        ToolInvocationLedgerEntity finished = ledgerFactService.finishToolInvocation(invocation, executionResult);
        log.info(
                "report invocation finished, invocationId={}, status={}, success={}, durationMs={}, errorCode={}",
                finished.getToolInvocationId(),
                finished.getStatus(),
                executionResult.isSuccess(),
                executionResult.getDurationMs(),
                executionResult.getErrorCode()
        );
        return executionResult;
    }

    /** 将通过 Java 协议校验的临时正文直接投影给当前 Live Run，绝不写入任何耐久模型。 */
    private void publishLiveDelta(
            ToolInvocationLedgerEntity invocation,
            AgentContext context,
            ReportContentDelta delta,
            LiveDeltaBudget budget
    ) {
        if (delta == null || budget == null || !budget.accept(delta)) {
            return;
        }
        ReportContentDeltaPayload payload = new ReportContentDeltaPayload(
                invocation.getToolInvocationId(),
                invocation.getToolName(),
                delta.getOutputType(),
                delta.getChunkIndex(),
                delta.getContent()
        );
        runtimeEventPublisher.publish(new RuntimeEventDraft(
                com.flocier.domain.agent.runtime.event.RuntimeEventType.REPORT_CONTENT_DELTA,
                Instant.now(),
                invocation.getSessionId(),
                invocation.getRunId(),
                invocation.getRequestId(),
                agentId(context),
                payload
        ));
    }

    private ToolExecutionResult completed(
            ToolInvokeRequest request,
            Instant startedAt,
            ReportFinalResult result,
            String registeredArtifactId,
            int inputArtifactCount
    ) {
        if (result == null || !"completed".equals(result.getStatus())) {
            String errorCode = result == null ? "report_protocol_error"
                    : firstNonBlank(result.getErrorCode(), "report_failed");
            return failed(request, startedAt, errorCode, result == null ? "Report 没有返回终态结果。" : result.getSummary());
        }
        String finalArtifactId = artifactId(result);
        if (!StringUtils.hasText(finalArtifactId) || !finalArtifactId.equals(registeredArtifactId)) {
            return failed(
                    request,
                    startedAt,
                    "report_contract_invalid",
                    "Report 成功终态未对应已登记的正式 Artifact。"
            );
        }
        Instant finishedAt = Instant.now();
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", "completed");
        structured.put("outputType", result.getOutputType());
        structured.put("artifactId", finalArtifactId);
        structured.put("summary", limit(result.getSummary(), 1200));
        structured.put("inputArtifactCount", inputArtifactCount);
        structured.put("materialStatistics", safeStatistics(result.getMaterialStatistics()));
        structured.put("limitations", safeLimitations(result.getLimitations()));
        return ToolExecutionResult.builder()
                .toolName(name)
                .toolCallId(request == null ? null : request.getToolCallId())
                .sourceType(TOOL_TYPE)
                .success(true)
                .status("success")
                .arguments(copyArguments(request))
                .metadata(copyMetadata(request))
                .outputText(limit(result.getSummary(), 1200))
                .llmObservation(successObservation(result, finalArtifactId, inputArtifactCount))
                .structuredOutput(structured)
                .ledgerFacts(List.of())
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .artifactRefs(List.of(finalArtifactId))
                .attributes(new LinkedHashMap<>())
                .build();
    }

    private ToolExecutionResult failed(ToolInvokeRequest request, Instant startedAt, String errorCode, String errorMessage) {
        Instant finishedAt = Instant.now();
        String safeCode = firstNonBlank(errorCode, "report_execution_error");
        String safeMessage = limit(firstNonBlank(errorMessage, "Report 当前不可用。"), 1200);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", "failed");
        structured.put("errorCode", safeCode);
        structured.put("errorMessage", safeMessage);
        return ToolExecutionResult.builder()
                .toolName(name)
                .toolCallId(request == null ? null : request.getToolCallId())
                .sourceType(TOOL_TYPE)
                .success(false)
                .status("failed")
                .errorCode(safeCode)
                .errorMessage(safeMessage)
                .arguments(copyArguments(request))
                .metadata(copyMetadata(request))
                .outputText(safeMessage)
                .llmObservation(failureObservation(safeCode, safeMessage))
                .structuredOutput(structured)
                .ledgerFacts(List.of())
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .artifactRefs(List.of())
                .attributes(new LinkedHashMap<>())
                .build();
    }

    private String successObservation(ReportFinalResult result, String artifactId, int inputArtifactCount) {
        StringBuilder observation = new StringBuilder("报告已生成并登记为正式 Artifact。")
                .append("输出格式：").append(result.getOutputType())
                .append("；Artifact ID：").append(artifactId)
                .append("；已使用 ").append(inputArtifactCount).append(" 份输入材料。")
                .append("摘要：").append(limit(result.getSummary(), 1000))
                .append("。可通过 Artifact 预览或下载阅读全文；如需继续推理，应使用该 Artifact 的受控读取能力。");
        List<String> limitations = safeLimitations(result.getLimitations());
        if (!limitations.isEmpty()) {
            observation.append("限制：").append(String.join("；", limitations)).append("。");
        }
        return limit(observation.toString(), OBSERVATION_LIMIT);
    }

    private String failureObservation(String errorCode, String message) {
        return limit(
                "Report 未能生成可信交付物（" + errorCode + "）。原因：" + message
                        + "。不要假设报告已经产出；可检查输入 Artifact、调整任务，或改用其他能力。",
                OBSERVATION_LIMIT
        );
    }

    private String artifactId(ReportFinalResult result) {
        if (result == null || result.getArtifact() == null) {
            return null;
        }
        Object value = result.getArtifact().get("artifactId");
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Integer> safeStatistics(Map<String, Integer> statistics) {
        return statistics == null ? Map.of() : new LinkedHashMap<>(statistics);
    }

    private List<String> safeLimitations(List<String> limitations) {
        if (limitations == null) {
            return List.of();
        }
        List<String> safe = new ArrayList<>();
        for (String limitation : limitations) {
            if (StringUtils.hasText(limitation)) {
                safe.add(limit(limitation, 240));
            }
            if (safe.size() >= 12) {
                break;
            }
        }
        return List.copyOf(safe);
    }

    private Map<String, Object> copyArguments(ToolInvokeRequest request) {
        return request == null || request.getArguments() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getArguments());
    }

    private Map<String, Object> copyMetadata(ToolInvokeRequest request) {
        return request == null || request.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getMetadata());
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String agentId(AgentContext context) {
        Object value = context == null ? null : context.getAttribute("agentId");
        return value == null ? null : String.valueOf(value);
    }

    /** 防止异常上游以无限小片段占满 Live SSE 与浏览器临时状态。 */
    private static final class LiveDeltaBudget {
        private int acceptedChunks;
        private int acceptedChars;

        private boolean accept(ReportContentDelta delta) {
            if (delta.getChunkIndex() == null || delta.getChunkIndex() <= acceptedChunks
                    || acceptedChunks >= MAX_LIVE_DELTA_CHUNKS || delta.getContent() == null) {
                return false;
            }
            int contentLength = delta.getContent().length();
            if (contentLength > ReportContentDeltaPayload.MAX_CONTENT_LENGTH
                    || acceptedChars + contentLength > MAX_LIVE_DELTA_CHARS) {
                return false;
            }
            acceptedChunks++;
            acceptedChars += contentLength;
            return true;
        }
    }
}
