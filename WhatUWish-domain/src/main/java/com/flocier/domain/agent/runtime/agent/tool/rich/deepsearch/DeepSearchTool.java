package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.rich.RichTool;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java 侧 DeepSearch Rich Tool。
 *
 * <p>它将 Python 独立研究 API 接入当前 ReAct 运行时：先建立 Java Tool Invocation，消费 Python SSE，
 * 再以一个受控的最终结果结束同一调用。阶段事件和 artifact 即时落账属于后续阶段，不能在这里伪造。</p>
 */
@Slf4j
public class DeepSearchTool implements RichTool {

    private static final int OBSERVATION_MAX_LENGTH = 3200;

    public static final String TOOL_TYPE = "rich_python";
    private final String name;
    private final String description;
    private final Object params;
    private final String pythonService;
    private final String pythonEndpoint;
    private final DeepSearchStreamClient streamClient;
    private final LedgerFactService ledgerFactService;
    private final DeepSearchRuntimeFactService runtimeFactService;

    public DeepSearchTool(String name,
                          String description,
                          Object params,
                          String pythonService,
                          String pythonEndpoint,
                          DeepSearchStreamClient streamClient,
                          LedgerFactService ledgerFactService) {
        this(name, description, params, pythonService, pythonEndpoint, streamClient, ledgerFactService, null);
    }

    public DeepSearchTool(String name,
                          String description,
                          Object params,
                          String pythonService,
                          String pythonEndpoint,
                          DeepSearchStreamClient streamClient,
                          LedgerFactService ledgerFactService,
                          DeepSearchRuntimeFactService runtimeFactService) {
        this.name = name;
        this.description = description;
        this.params = params;
        this.pythonService = pythonService;
        this.pythonEndpoint = pythonEndpoint;
        this.streamClient = streamClient;
        this.ledgerFactService = ledgerFactService;
        this.runtimeFactService = runtimeFactService;
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
            return failed(null, Instant.now(), "invalid_deep_search_input",
                    "DeepSearchTool only accepts ToolInvokeRequest input");
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
                "deep_search_execution_error", "DeepSearch 未返回受控执行结果。");
    }

    @Override
    public ToolExecutionResult executeRich(ToolInvokeRequest request,
                                           AgentContext context,
                                           ToolInvocationLedgerEntity invocation,
                                           Instant startedAt) {
        log.info("deep search invocation started, invocationId={}, toolCallId={}, runId={}, requestId={}",
                invocation.getToolInvocationId(), request.getToolCallId(), invocation.getRunId(), request.getRequestId());
        ToolExecutionResult executionResult;
        try {
            DeepSearchStartRequest startRequest = DeepSearchStartRequest.from(request, invocation.getToolInvocationId());
            ToolExecutionResult priorTerminal = findTerminalDuplicate(context, startRequest);
            if (priorTerminal != null) {
                executionResult = duplicateTerminalResult(request, startedAt, priorTerminal);
                ToolInvocationLedgerEntity finishedInvocation = ledgerFactService.finishToolInvocation(invocation, executionResult);
                log.info("deep search duplicate scope prevented, invocationId={}, priorToolCallId={}, durationMs={}",
                        finishedInvocation.getToolInvocationId(), priorTerminal.getToolCallId(), executionResult.getDurationMs());
                return executionResult;
            }
            startRuntimeExecution(invocation, startRequest);
            DeepSearchEventSummary eventSummary = new DeepSearchEventSummary();
            DeepSearchFinalResult result = streamClient.stream(pythonService, pythonEndpoint, startRequest, event -> {
                persistRuntimeEvent(invocation, context, event);
                eventSummary.accept(event);
                log.info("deep search stream event received, invocationId={}, sequence={}, type={}, phase={}, status={}, summaryLength={}",
                        invocation.getToolInvocationId(), event.getSequence(), event.getStreamEventType(), event.getPhase(),
                        event.getStatus(), event.getSummary().length());
            });
            log.info("deep search final result received, invocationId={}, status={}, sources={}, evidence={}, artifacts={}, progressEvents={}, artifactEvents={}, errorEvents={}",
                    invocation.getToolInvocationId(), result.getStatus(), size(result.getSources()), size(result.getEvidence()),
                    size(result.getArtifacts()), eventSummary.getProgressCount(), eventSummary.getArtifactCount(), eventSummary.getErrorCount());
            executionResult = completed(request, startedAt, result, eventSummary);
            finishRuntimeExecution(invocation, result, executionResult.isSuccess(), executionResult.getErrorCode(), executionResult.getErrorMessage());
        } catch (DeepSearchStreamException exception) {
            executionResult = failed(request, startedAt, exception.getErrorCode(), exception.getMessage());
            finishRuntimeExecution(invocation, null, false, exception.getErrorCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("deep search rich tool failed, toolCallId={}, requestId={}", request.getToolCallId(), request.getRequestId(), exception);
            executionResult = failed(request, startedAt, "deep_search_execution_error",
                    "DeepSearch execution failed before a trusted final result was received");
            finishRuntimeExecution(invocation, null, false, "deep_search_execution_error", executionResult.getErrorMessage());
        }
        // 终态事实未成功保存时不得把结果交回 ReAct，也不得触发最终 Runtime Event。
        ToolInvocationLedgerEntity finishedInvocation = ledgerFactService.finishToolInvocation(invocation, executionResult);
        log.info("deep search invocation finished, invocationId={}, status={}, success={}, durationMs={}, errorCode={}",
                finishedInvocation.getToolInvocationId(), finishedInvocation.getStatus(), executionResult.isSuccess(),
                executionResult.getDurationMs(), executionResult.getErrorCode());
        return executionResult;
    }

    private void startRuntimeExecution(ToolInvocationLedgerEntity invocation, DeepSearchStartRequest request) {
        if (runtimeFactService != null) {
            runtimeFactService.start(invocation, request);
        }
    }

    private void persistRuntimeEvent(ToolInvocationLedgerEntity invocation, AgentContext context, DeepSearchStreamEvent event) {
        if (runtimeFactService != null) {
            runtimeFactService.accept(invocation, context, event);
        }
    }

    private void finishRuntimeExecution(ToolInvocationLedgerEntity invocation, DeepSearchFinalResult result,
                                        boolean success, String errorCode, String errorMessage) {
        if (runtimeFactService != null) {
            runtimeFactService.finish(invocation, result, success, errorCode, errorMessage);
        }
    }

    private ToolExecutionResult completed(ToolInvokeRequest request,
                                          Instant startedAt,
                                          DeepSearchFinalResult result,
                                          DeepSearchEventSummary eventSummary) {
        String disposition = firstNonBlank(result.getDeliveryDisposition(), "failed_unrecoverable");
        boolean terminal = "terminal_completed".equalsIgnoreCase(disposition)
                || "terminal_partial".equalsIgnoreCase(disposition);
        // Python 的 completed 语义以已保存的 Research Evidence Package 为前提。Java 必须防御
        // 协议异常，不能把“没有可引用产物”的终态摘要交给 ReAct 继续消费。
        if (terminal && artifactIds(result).isEmpty()) {
            return failed(request, startedAt, "deep_search_contract_invalid",
                    "DeepSearch terminal delivery did not include a research artifact reference");
        }
        boolean succeeded = terminal;
        String status = succeeded ? "success" : "failed";
        String errorCode = succeeded ? null : firstNonBlank(result.getStopReason(), "deep_search_failed");
        String errorMessage = succeeded ? null : result.getSummary();
        Map<String, Object> structured = result.toControlledSummary();
        structured.put("receivedProgressEvents", eventSummary.getProgressCount());
        structured.put("receivedArtifactEvents", eventSummary.getArtifactCount());
        structured.put("receivedErrorEvents", eventSummary.getErrorCount());
        structured.put("lastPhase", eventSummary.getLastPhase());
        Instant finishedAt = Instant.now();
        return ToolExecutionResult.builder()
                .toolName(name)
                .toolCallId(request == null ? null : request.getToolCallId())
                .sourceType(TOOL_TYPE)
                .success(succeeded)
                .status(status)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .arguments(copyArguments(request))
                .metadata(copyMetadata(request))
                .outputText(result.getSummary())
                .llmObservation(succeeded ? buildObservation(result, eventSummary) : buildFailedFinalObservation(result))
                .structuredOutput(structured)
                .ledgerFacts(List.of())
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .artifactRefs(artifactIds(result))
                .attributes(new LinkedHashMap<>())
                .build();
    }

    private ToolExecutionResult failed(ToolInvokeRequest request, Instant startedAt, String errorCode, String errorMessage) {
        Instant finishedAt = Instant.now();
        String observation;
        if ("deep_search_contract_invalid".equals(errorCode)) {
            observation = "DeepSearch 返回了不符合 Rich Tool 交付契约的终态结果。原因："
                    + firstNonBlank(errorMessage, "unknown contract error")
                    + "。Java 已拒绝将它作为研究完成结果；不要按原参数立即重试，应等待工具服务修复或改用其他能力。";
        } else {
            observation = "DeepSearch 未能获得可信的最终研究结果。原因：" + firstNonBlank(errorMessage, "unknown error")
                    + "。该错误来自传输或服务层，主 Agent 可改用其他能力或向用户说明当前外部依赖不可用。";
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", "failed");
        structured.put("errorCode", errorCode);
        structured.put("errorMessage", errorMessage);
        return ToolExecutionResult.builder()
                .toolName(name)
                .toolCallId(request == null ? null : request.getToolCallId())
                .sourceType(TOOL_TYPE)
                .success(false)
                .status("failed")
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .arguments(copyArguments(request))
                .metadata(copyMetadata(request))
                .outputText(errorMessage)
                .llmObservation(observation)
                .structuredOutput(structured)
                .ledgerFacts(List.of())
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .artifactRefs(List.of())
                .attributes(new LinkedHashMap<>())
                .build();
    }

    private String buildObservation(DeepSearchFinalResult result, DeepSearchEventSummary events) {
        StringBuilder text = new StringBuilder();
        text.append("DeepSearch 当前研究范围已完成并交付来源材料包。")
                .append("研究摘要：").append(limit(result.getSummary(), 1600));
        String outcome = firstNonBlank(result.getResearchOutcome(), "evidence_limited");
        if ("evidence_sufficient".equalsIgnoreCase(outcome)) {
            text.append("\n材料状态：已基于抓取来源形成研究摘要，仍需按来源链接核对关键事实。");
        } else if ("evidence_insufficient".equalsIgnoreCase(outcome)) {
            text.append("\n材料状态：未形成足够的公开来源材料，请如实说明限制。");
        } else {
            text.append("\n材料状态：已交付可用来源材料，但自动综合或材料范围仍有限制；后续回答必须保留这些限制。");
        }
        int candidateSourceCount = size(result.getSources());
        int fetchedSourceCount = fetchedSourceCount(result);
        text.append("\n来源候选数：").append(candidateSourceCount);
        if (fetchedSourceCount > 0) {
            text.append("；成功抓取来源数：").append(fetchedSourceCount);
        }
        text.append("；已接收阶段事件：").append(events.getProgressCount() + events.getArtifactCount()).append("。");
        if (result.getLimitations() != null && !result.getLimitations().isEmpty()) {
            text.append("\n限制：").append(limit(String.join("；", result.getLimitations()), 700));
        }
        if (StringUtils.hasText(result.getStopReason())) {
            text.append("\n停止原因：").append(limit(result.getStopReason(), 300));
        }
        List<String> artifactIds = artifactIds(result);
        if (!artifactIds.isEmpty()) {
            text.append("\n已交付研究材料 Artifact ID：").append(String.join(", ", artifactIds)).append("。")
                    .append("如需代码分析，可将该 ID 放入 code_execution 或 code_interpreter 的 sourceArtifactIds；")
                    .append("导入后读取 inputs/artifacts/<Artifact ID>-deepsearch-source-package.md。")
                    .append("若用户需要正式报告，可直接调用 report_tool，")
                    .append("该工具会受控使用本次已交付的研究材料；无需先读取全文或手工传递内部文件信息。")
                    .append("请基于该来源材料包和上述限制完成当前回答，")
                    .append("或交给后续报告交付能力；不要重调相同研究范围。");
        }
        return limit(text.toString(), OBSERVATION_MAX_LENGTH);
    }

    /**
     * Python 新主链会返回全部候选来源，每项通过 status 标识是否真的抓取成功。
     * 不能把候选数当成交付材料数，否则 ReAct 会高估当前资料覆盖度。
     */
    private int fetchedSourceCount(DeepSearchFinalResult result) {
        if (result == null || result.getSources() == null || result.getSources().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map<String, Object> source : result.getSources()) {
            if (source != null && "fetched".equalsIgnoreCase(String.valueOf(source.get("status")))) {
                count++;
            }
        }
        return count;
    }

    /** Python 可以用合法的最终 result 表达业务失败；这与传输或协议失败不同，但都不能伪装成研究完成。 */
    private String buildFailedFinalObservation(DeepSearchFinalResult result) {
        boolean callerAction = "caller_action_required".equalsIgnoreCase(result.getDeliveryDisposition());
        StringBuilder text = new StringBuilder("DeepSearch 已结束，但未产出可直接作为研究结论使用的结果。原因：")
                .append(limit(result.getSummary(), 1000));
        if (StringUtils.hasText(result.getStopReason())) {
            text.append("。停止原因：").append(limit(result.getStopReason(), 300));
        }
        if (result.getLimitations() != null && !result.getLimitations().isEmpty()) {
            text.append("。已知限制：").append(limit(String.join("；", result.getLimitations()), 700));
        }
        if (callerAction) {
            text.append("。调用方动作：").append(firstNonBlank(result.getCallerAction(), "check_configuration"))
                    .append("。请先处理该外部前提，不要立即以原参重试。");
        } else {
            text.append("。这是不可恢复的外部或协议失败，避免立即以相同参数重复调用；可改用其他能力或向用户说明限制。");
        }
        return limit(text.toString(), OBSERVATION_MAX_LENGTH);
    }

    /**
     * 只读取当前 AgentContext 已有的运行内结果。它不是跨会话缓存，也不改变普通工具执行器。
     * ReAct 会在每次工具调用结束后把结果追加进 context，因此下一次同语义调用能在这里收敛。
     */
    private ToolExecutionResult findTerminalDuplicate(AgentContext context, DeepSearchStartRequest startRequest) {
        if (context == null || context.getToolExecutionResults() == null) return null;
        String currentFingerprint = DeepSearchScopeFingerprint.fromPayload(startRequest.getPayload());
        for (ToolExecutionResult prior : context.getToolExecutionResults()) {
            if (prior == null || !name.equals(prior.getToolName()) || !(prior.getStructuredOutput() instanceof Map<?, ?> output)) {
                continue;
            }
            Object disposition = output.get("deliveryDisposition");
            Object fingerprint = output.get("researchScopeFingerprint");
            if (("terminal_completed".equals(disposition) || "terminal_partial".equals(disposition))
                    && Objects.equals(currentFingerprint, fingerprint)) {
                return prior;
            }
        }
        return null;
    }

    private ToolExecutionResult duplicateTerminalResult(ToolInvokeRequest request, Instant startedAt, ToolExecutionResult prior) {
        Instant finishedAt = Instant.now();
        Map<String, Object> structured = prior.getStructuredOutput() instanceof Map<?, ?> values
                ? new LinkedHashMap<>((Map<String, Object>) values) : new LinkedHashMap<>();
        structured.put("duplicateScopePrevented", true);
        String observation = "DeepSearch 已在当前 Run 中完成相同研究范围。请直接使用此前的研究摘要和 artifact，"
                + "不要以相同范围重新检索。此前结果：" + limit(prior.getPreferredObservationText(), 1200);
        return ToolExecutionResult.builder()
                .toolName(name).toolCallId(request == null ? null : request.getToolCallId()).sourceType(TOOL_TYPE)
                .success(true).status("success").arguments(copyArguments(request)).metadata(copyMetadata(request))
                .outputText(String.valueOf(structured.getOrDefault("summary", "DeepSearch 已完成相同范围研究。")))
                .llmObservation(observation).structuredOutput(structured).ledgerFacts(List.of())
                .startedAt(startedAt).finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .artifactRefs(prior.getArtifactRefs() == null ? List.of() : List.copyOf(prior.getArtifactRefs()))
                .attributes(new LinkedHashMap<>()).build();
    }

    private List<String> artifactIds(DeepSearchFinalResult result) {
        if (result == null || result.getArtifacts() == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (ArtifactRuntimeDescriptor artifact : result.getArtifacts()) {
            if (artifact != null && StringUtils.hasText(artifact.getArtifactId())) {
                ids.add(artifact.getArtifactId());
            }
        }
        return List.copyOf(ids);
    }

    private Map<String, Object> copyArguments(ToolInvokeRequest request) {
        return request == null || request.getArguments() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getArguments());
    }

    private Map<String, Object> copyMetadata(ToolInvokeRequest request) {
        return request == null || request.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getMetadata());
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private String limit(String text, int maxLength) {
        if (text == null) return "";
        String normalized = text.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    /** 本阶段只汇总已接收事件数量，避免把中间事件误当作已经持久化的运行事实。 */
    private static class DeepSearchEventSummary {
        private int progressCount;
        private int artifactCount;
        private int errorCount;
        private String lastPhase;

        void accept(DeepSearchStreamEvent event) {
            if (event == null) return;
            if ("artifact".equals(event.getStreamEventType())) artifactCount++;
            else if ("error".equals(event.getStreamEventType())) errorCount++;
            else progressCount++;
            lastPhase = event.getPhase();
        }

        int getProgressCount() { return progressCount; }
        int getArtifactCount() { return artifactCount; }
        int getErrorCount() { return errorCount; }
        String getLastPhase() { return lastPhase; }
    }
}
