package com.flocier.domain.agent.runtime.agent.tool;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.AgentRunMode;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeLedgerRecorder;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeCollector;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeFormatter;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.agent.runtime.artifact.WorkspaceArtifactDeliveryService;
import com.flocier.domain.agent.runtime.agent.tool.impl.McpToolAdapter;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolAdapter;
import com.flocier.domain.agent.runtime.agent.tool.impl.MaterialTool;
import com.flocier.domain.agent.runtime.agent.tool.cli.OpenCliArgv;
import com.flocier.domain.agent.runtime.skill.SkillExecutionResult;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认工具执行器。
 * 这里只负责工具查找、执行、结果归一化、耗时统计和 ledger 分发，
 * 不再承担普通工具成功/失败语义的统一“脑补”职责。
 */
@Slf4j
@Component
public class DefaultToolRunner implements ToolRunner {

    @Resource
    private LedgerFactService ledgerFactService;

    @Resource
    private ArtifactRuntimeLedgerRecorder artifactRuntimeLedgerRecorder;
    @Resource
    private ArtifactRuntimeCollector artifactRuntimeCollector;
    @Resource
    private ArtifactRuntimeFormatter artifactRuntimeFormatter;
    @Resource
    private WorkspaceArtifactDeliveryService workspaceArtifactDeliveryService;

    @Override
    public ToolExecutionResult run(ToolCollection toolCollection, ToolInvokeRequest request, AgentContext context) {
        return execute(prepare(toolCollection, request, context), context);
    }

    @Override
    public PreparedToolExecution prepare(ToolCollection toolCollection, ToolInvokeRequest request, AgentContext context) {
        Instant startedAt = Instant.now();
        log.info("tool execution prepared, toolName={}, toolCallId={}, requestId={}, sessionId={}, runId={}, step={}, arguments={}",
                request.getToolName(),
                request.getToolCallId(),
                request.getRequestId(),
                request.getSessionId(),
                context == null ? null : context.getAttribute("runId"),
                request.getStep(),
                recordableArguments(request));

        try { AgentRunMode.checkTool(context, request.getToolName()); }
        catch (RuntimeException e) { return PreparedToolExecution.failed(request, startedAt,
                buildInfrastructureFailureResult(request, startedAt, "review_tool_denied", e.getMessage(), e.getMessage())); }
        BaseTool tool;
        try {
            tool = toolCollection.getRequired(request.getToolName());
        } catch (Exception e) {
            ToolInvocationLedgerEntity invocation;
            try {
                invocation = startInvocation(context, request, request.getToolName(), request.getSourceHint());
            } catch (RuntimeException ledgerException) {
                return PreparedToolExecution.failed(request, startedAt, ledgerStartFailure(request, startedAt, ledgerException));
            }
            ToolExecutionResult result = buildInfrastructureFailureResult(
                    request,
                    startedAt,
                    "tool_not_found",
                    e.getMessage(),
                    "当前运行时未找到工具 " + request.getToolName() + "，请改用其他已注册工具。"
            );
            logFailure(context, request, result, e, false);
            finishInvocation(context, request, invocation, result);
            return PreparedToolExecution.failed(request, startedAt, result);
        }

        return prepareResolved(tool, request, context, resolveSourceType(tool, request), startedAt);
    }

    /** 供 Rich Tool 分流器复用的预备入口，避免 ReAct 自行了解来源类型或账本生命周期。 */
    public PreparedToolExecution prepareResolved(BaseTool tool,
                                                 ToolInvokeRequest request,
                                                 AgentContext context,
                                                 String sourceType,
                                                 Instant startedAt) {
        // 分流器直接进入此方法时也先校验权限，避免将拒绝误报为账本故障。
        try { AgentRunMode.checkTool(context, tool.name()); }
        catch (RuntimeException e) { return PreparedToolExecution.failed(request, startedAt,
                buildInfrastructureFailureResult(request, startedAt, "review_tool_denied", e.getMessage(), e.getMessage())); }
        ToolInvocationLedgerEntity invocation;
        try {
            invocation = startInvocation(context, request, tool.name(), sourceType);
        } catch (RuntimeException e) {
            return PreparedToolExecution.failed(request, startedAt, ledgerStartFailure(request, startedAt, e));
        }
        if (invocation != null && !"RUNNING".equalsIgnoreCase(invocation.getStatus())) {
            return PreparedToolExecution.failed(request, startedAt, buildInfrastructureFailureResult(request, startedAt, "tool_invocation_already_finished",
                    "Tool invocation already reached terminal status: " + invocation.getStatus(),
                    "该工具调用标识已经完成，运行时已阻止重复执行。请基于已有结果继续决策。"));
        }
        return PreparedToolExecution.ready(request, tool, invocation, startedAt);
    }

    @Override
    public ToolExecutionResult execute(PreparedToolExecution prepared, AgentContext context) {
        if (prepared == null) {
            Instant now = Instant.now();
            return ToolExecutionResult.builder()
                    .toolName("unknown_tool")
                    .success(false)
                    .status("failed")
                    .errorCode("tool_prepare_missing")
                    .errorMessage("Prepared tool execution is required")
                    .outputText("工具调用未能完成可信预备，因此运行时没有执行该工具。")
                    .llmObservation("工具调用未能完成可信预备，因此运行时没有执行该工具。")
                    .structuredOutput(Map.of("errorCode", "tool_prepare_missing"))
                    .ledgerFacts(List.of())
                    .artifactRefs(List.of())
                    .attributes(new LinkedHashMap<>())
                    .startedAt(now)
                    .finishedAt(now)
                    .durationMs(0L)
                    .build();
        }
        if (!prepared.isReady()) {
            return prepared.getPreparationFailure();
        }
        ToolInvokeRequest request = prepared.getRequest();
        BaseTool tool = prepared.getTool();
        ToolInvocationLedgerEntity invocation = prepared.getInvocation();
        Instant startedAt = prepared.getStartedAt();
        try {
            AgentRunMode.checkTool(context, tool.name());
            Object payload = tool.execute(request, context);
            Instant finishedAt = Instant.now();
            ToolPayloadView payloadView = normalizePayload(tool.name(), payload, context);
            ToolExecutionResult result = ToolExecutionResult.builder()
                    .toolName(tool.name())
                    .toolCallId(request.getToolCallId())
                    .sourceType(resolveSourceType(tool, request))
                    .success(payloadView.success())
                    .status(resolveStatus(payloadView))
                    .errorCode(payloadView.errorCode())
                    .errorMessage(payloadView.errorMessage())
                    .arguments(recordableArguments(request))
                    .metadata(copyMap(request.getMetadata()))
                    .outputText(payloadView.toolResult())
                    .llmObservation(payloadView.llmObservation())
                    .structuredOutput(payloadView.structuredOutput())
                    .ledgerFacts(copyLedgerFacts(payloadView.ledgerFacts()))
                    .startedAt(startedAt)
                    .finishedAt(finishedAt)
                    .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                    .artifactRefs(copyArtifactRefs(payloadView.artifactRefs()))
                    .artifactDescriptors(copyArtifactDescriptors(payloadView.artifactDescriptors()))
                    .attributes(new LinkedHashMap<>())
                    .build();
            enrichSkillAttributes(payload, result);
            // 材料产物先经账本批量登记；不能预先把尚未提交的 ID 写入运行 Registry。
            if (!AgentRunMode.isReview(context) && !(tool instanceof MaterialTool))
                registerArtifactsAndEnrichObservation(context, request, result);
            if (!AgentRunMode.isReview(context) && tool instanceof MaterialTool)
                workspaceArtifactDeliveryService.register(context, request, result, invocation);
            // 产物登记可能把材料工具结果改成失败；日志必须记录最终可见的状态。
            log.info("tool run finished, toolName={}, toolCallId={}, requestId={}, runId={}, success={}, status={}, durationMs={}, observation={}",
                    result.getToolName(),
                    result.getToolCallId(),
                    request.getRequestId(),
                    context == null ? null : context.getAttribute("runId"),
                    result.isSuccess(),
                    result.getStatus(),
                    result.getDurationMs(),
                    preview(result.getPreferredObservationText()));
            ToolInvocationLedgerEntity finishedInvocation = finishInvocation(context, request, invocation, result);
            if (context != null && "RequestDesktopControl".equals(tool.name()) && finishedInvocation != null)
                context.setAttribute("desktopControl.toolInvocationId", finishedInvocation.getToolInvocationId());
            if (!AgentRunMode.isReview(context)) {
                if (!(tool instanceof MaterialTool)) {
                    recordArtifacts(context, request, result, finishedInvocation);
                }
            }
            return result;
        } catch (Exception e) {
            ToolExecutionResult result = buildInfrastructureFailureResult(
                    request,
                    startedAt,
                    "tool_execute_error",
                    e.getMessage(),
                    "工具 " + request.getToolName() + " 执行异常：" + safe(e.getMessage())
            );
            logFailure(context, request, result, e, true);
            finishInvocation(context, request, invocation, result);
            return result;
        }
    }

    private void logFailure(AgentContext context,
                            ToolInvokeRequest request,
                            ToolExecutionResult result,
                            Exception exception,
                            boolean includeStackTrace) {
        if (includeStackTrace) {
            log.error("tool run failed, toolName={}, toolCallId={}, requestId={}, runId={}, durationMs={}, error={}",
                    result.getToolName(),
                    result.getToolCallId(),
                    request.getRequestId(),
                    context == null ? null : context.getAttribute("runId"),
                    result.getDurationMs(),
                    exception == null ? null : exception.getMessage(),
                    exception);
        } else {
            log.warn("tool run failed before execute, toolName={}, toolCallId={}, requestId={}, runId={}, observation={}",
                    request.getToolName(),
                    request.getToolCallId(),
                    request.getRequestId(),
                    context == null ? null : context.getAttribute("runId"),
                    preview(result.getPreferredObservationText()));
        }
    }

    private ToolInvocationLedgerEntity startInvocation(AgentContext context, ToolInvokeRequest request,
                                                       String toolName, String toolType) {
        if (ledgerFactService == null) {
            return null;
        }
        ToolInvokeRequest recorded = request;
        Map<String, Object> safeArguments = recordableArguments(request);
        if (safeArguments != request.getArguments()) {
            recorded = ToolInvokeRequest.builder().toolName(request.getToolName()).toolCallId(request.getToolCallId())
                    .arguments(safeArguments).metadata(request.getMetadata()).sourceHint(request.getSourceHint())
                    .requestId(request.getRequestId()).sessionId(request.getSessionId()).step(request.getStep()).build();
        }
        return ledgerFactService.startToolInvocation(context, recorded, toolName,
                StringUtils.hasText(toolType) ? toolType : "unknown");
    }

    /** 敏感浏览器 argv 仅在执行链中使用；准备日志和调用 Ledger 只记录命令类别。 */
    private Map<String, Object> recordableArguments(ToolInvokeRequest request) {
        if (request == null || request.getArguments() == null) return Map.of();
        if (!"browser".equals(request.getToolName()) && !"agent_browser".equals(request.getToolName()))
            return request.getArguments();
        Object raw = request.getArguments().get("args");
        if (!(raw instanceof List<?> items)) return request.getArguments();
        List<String> args = items.stream().map(String::valueOf).toList();
        if (!OpenCliArgv.isSensitive(args)) return request.getArguments();
        Map<String, Object> safe = new LinkedHashMap<>(request.getArguments());
        safe.put("args", List.of("[redacted:" + OpenCliArgv.sensitiveKind(args) + "]"));
        return safe;
    }

    private ToolInvocationLedgerEntity finishInvocation(AgentContext context, ToolInvokeRequest request,
                                                        ToolInvocationLedgerEntity invocation,
                                                        ToolExecutionResult result) {
        if (ledgerFactService == null || invocation == null) {
            return invocation;
        }
        try {
            return ledgerFactService.finishToolInvocation(invocation, result);
        } catch (RuntimeException e) {
            result.putAttribute("ledgerLifecycle", "finish_failed");
            log.error("tool result ledger finish failed, toolName={}, toolCallId={}, requestId={}, runId={}, reason={}",
                    result.getToolName(), request.getToolCallId(), request.getRequestId(),
                    context == null ? null : context.getAttribute("runId"), e.getMessage(), e);
            return null;
        }
    }

    private ToolExecutionResult ledgerStartFailure(ToolInvokeRequest request, Instant startedAt, RuntimeException exception) {
        ToolExecutionResult result = buildInfrastructureFailureResult(request, startedAt,
                "tool_ledger_start_failed", exception.getMessage(),
                "工具调用未能建立可信的开始事实，因此运行时没有执行该工具。请稍后重试或检查账本服务。");
        result.putAttribute("ledgerLifecycle", "start_failed");
        log.error("tool invocation ledger start failed, toolName={}, toolCallId={}, requestId={}, reason={}",
                request.getToolName(), request.getToolCallId(), request.getRequestId(), exception.getMessage(), exception);
        return result;
    }

    private void recordArtifacts(AgentContext context,
                                 ToolInvokeRequest request,
                                 ToolExecutionResult result,
                                 ToolInvocationLedgerEntity toolInvocation) {
        if (artifactRuntimeLedgerRecorder != null) {
            artifactRuntimeLedgerRecorder.recordToolArtifacts(context, request, result, toolInvocation);
        }
    }

    /**
     * 先把工具已明确声明的真实文件登记到本次 run 的 registry，
     * 再将可见摘要写入 observation。之后 ledger 记录的内容就是 LLM 实际可见内容。
     */
    private void registerArtifactsAndEnrichObservation(AgentContext context,
                                                       ToolInvokeRequest request,
                                                       ToolExecutionResult result) {
        if (context == null || artifactRuntimeCollector == null || artifactRuntimeFormatter == null) {
            return;
        }
        ArtifactRuntimeSource source = ArtifactRuntimeSource.from(context, request, result == null ? null : result.getToolName());
        context.requireArtifactRuntimeRegistry().registerAll(source, artifactRuntimeCollector.collect(request, result, context));
        result.setLlmObservation(artifactRuntimeFormatter.appendToolArtifactSummary(
                result.getLlmObservation(), context.requireArtifactRuntimeRegistry().findByToolCallId(source.getToolCallId())));
    }

    /**
     * 这里只处理运行时基础设施失败，不处理具体业务工具的语义失败。
     */
    private ToolExecutionResult buildInfrastructureFailureResult(ToolInvokeRequest request,
                                                                 Instant startedAt,
                                                                 String errorCode,
                                                                 String errorMessage,
                                                                 String llmObservation) {
        Instant finishedAt = Instant.now();
        return ToolExecutionResult.builder()
                .toolName(request.getToolName())
                .toolCallId(request.getToolCallId())
                .sourceType(request.getSourceHint())
                .success(false)
                .status("failed")
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .arguments(copyMap(request.getArguments()))
                .metadata(copyMap(request.getMetadata()))
                .outputText(errorMessage)
                .llmObservation(llmObservation)
                .structuredOutput(Map.of(
                        "toolName", request.getToolName(),
                        "errorCode", errorCode,
                        "errorMessage", errorMessage
                ))
                .ledgerFacts(List.of(Map.of(
                        "factType", "tool_runtime_failure",
                        "toolName", request.getToolName(),
                        "errorCode", errorCode,
                        "errorMessage", errorMessage
                )))
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .artifactRefs(List.of())
                .attributes(new LinkedHashMap<>())
                .build();
    }

    private String resolveSourceType(BaseTool tool, ToolInvokeRequest request) {
        if (request.getSourceHint() != null && !request.getSourceHint().isBlank()) {
            return request.getSourceHint();
        }
        if (tool instanceof PythonToolAdapter) {
            return "python";
        }
        if (tool instanceof McpToolAdapter) {
            return "mcp";
        }
        if (isSkillRuntimeTool(tool.name())) {
            return "skill";
        }
        return "java";
    }

    private boolean isSkillRuntimeTool(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "skill_tool",
                 "skill_view",
                 "skills_search",
                 "skill_read_tool",
                 "skill_list_directory_tool",
                 "skill_glob_tool",
                 "skill_grep_tool",
                 "skill_script_runner_tool",
                 "skill_execute_tool" -> true;
            default -> false;
        };
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(source);
    }

    private List<String> copyArtifactRefs(List<String> artifactRefs) {
        if (artifactRefs == null || artifactRefs.isEmpty()) {
            return List.of();
        }
        return List.copyOf(artifactRefs);
    }

    private List<Map<String, Object>> copyLedgerFacts(List<Map<String, Object>> ledgerFacts) {
        if (ledgerFacts == null || ledgerFacts.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copied = new java.util.ArrayList<>();
        for (Map<String, Object> item : ledgerFacts) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            copied.add(new LinkedHashMap<>(item));
        }
        return copied;
    }

    private List<Map<String, Object>> copyArtifactDescriptors(List<Map<String, Object>> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copied = new java.util.ArrayList<>();
        for (Map<String, Object> descriptor : descriptors) {
            if (descriptor != null && !descriptor.isEmpty()) {
                copied.add(new LinkedHashMap<>(descriptor));
            }
        }
        return copied;
    }

    private ToolPayloadView normalizePayload(String toolName, Object payload, AgentContext context) {
        if (payload instanceof ToolResultPayload resultPayload) {
            return fromToolPayload(toolName, resultPayload, context);
        }
        if (payload instanceof SkillExecutionResult skillResult) {
            return fromSkillResult(skillResult);
        }
        return fromLegacyPayload(payload);
    }

    private ToolPayloadView fromToolPayload(String toolName, ToolResultPayload payload, AgentContext context) {
        String llmObservation = ToolObservationBudget.apply(context, toolName, firstNonBlank(
                payload.getLlmObservation(),
                payload.getToolResult(),
                payload.getErrorMessage()
        ));
        Object structuredOutput = payload.getStructuredOutput() == null
                ? Map.of()
                : payload.getStructuredOutput();
        return new ToolPayloadView(
                payload.isSuccess(),
                resolveStatus(payload.getStatus(), payload.isSuccess()),
                payload.getErrorCode(),
                payload.getErrorMessage(),
                payload.getToolResult(),
                llmObservation,
                structuredOutput,
                payload.getLedgerFacts(),
                payload.getArtifactRefs(),
                payload.getArtifactDescriptors()
        );
    }

    private ToolPayloadView fromSkillResult(SkillExecutionResult skillResult) {
        return new ToolPayloadView(
                skillResult.isSuccess(),
                resolveStatus(skillResult.getStatus(), skillResult.isSuccess()),
                skillResult.getErrorCode(),
                skillResult.getErrorMessage(),
                firstNonBlank(skillResult.getFinalAnswer(), skillResult.getSummary()),
                firstNonBlank(skillResult.getLlmObservation(), skillResult.getFinalAnswer(), skillResult.getSummary()),
                skillResult.getStructuredOutput() == null ? Map.of() : new LinkedHashMap<>(skillResult.getStructuredOutput()),
                skillResult.getLedgerFacts(),
                skillResult.getArtifactRefs(),
                List.of()
        );
    }

    /**
     * 兼容层只做最小映射，不再给普通工具附加特化业务语义。
     */
    private ToolPayloadView fromLegacyPayload(Object payload) {
        String toolResult = toText(payload);
        String observation = limitText(toolResult, 1200);
        return new ToolPayloadView(
                true,
                "success",
                null,
                null,
                toolResult,
                observation,
                Map.of("text", observation),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private void enrichSkillAttributes(Object payload, ToolExecutionResult result) {
        if (!(payload instanceof SkillExecutionResult skillResult) || result == null) {
            return;
        }
        // Skill 作为特殊工具执行时，会把子空间上下文带回主链路，便于 ledger 和后续分析复用。
        result.putAttribute("skillName", skillResult.getSkillName());
        result.putAttribute("skillExecutionId", skillResult.getSkillExecutionId());
        result.putAttribute("skillResultSummary", skillResult.getSkillResultSummary());
        result.putAttribute("usedTools", skillResult.getUsedTools());
    }

    private String resolveStatus(ToolPayloadView payloadView) {
        return resolveStatus(payloadView.status(), payloadView.success());
    }

    private String resolveStatus(String explicitStatus, boolean success) {
        if (StringUtils.hasText(explicitStatus)) {
            return explicitStatus.trim();
        }
        return success ? "success" : "failed";
    }

    private String toText(Object payload) {
        if (payload == null) {
            return "";
        }
        if (payload instanceof String text) {
            return text;
        }
        try {
            return JSON.toJSONString(payload);
        } catch (Exception ignored) {
            return String.valueOf(payload);
        }
    }

    private String limitText(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return safe(value);
        }
        return value.substring(0, limit) + "...(truncated, total=" + value.length() + ")";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private static final class ToolPayloadView {
        private final boolean success;
        private final String status;
        private final String errorCode;
        private final String errorMessage;
        private final String toolResult;
        private final String llmObservation;
        private final Object structuredOutput;
        private final List<Map<String, Object>> ledgerFacts;
        private final List<String> artifactRefs;
        private final List<Map<String, Object>> artifactDescriptors;

        private ToolPayloadView(boolean success, String status, String errorCode, String errorMessage,
                                String toolResult, String llmObservation, Object structuredOutput,
                                List<Map<String, Object>> ledgerFacts, List<String> artifactRefs,
                                List<Map<String, Object>> artifactDescriptors) {
            this.success = success;
            this.status = status;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.toolResult = toolResult;
            this.llmObservation = llmObservation;
            this.structuredOutput = structuredOutput;
            this.ledgerFacts = ledgerFacts;
            this.artifactRefs = artifactRefs;
            this.artifactDescriptors = artifactDescriptors;
        }

        private boolean success() { return success; }
        private String status() { return status; }
        private String errorCode() { return errorCode; }
        private String errorMessage() { return errorMessage; }
        private String toolResult() { return toolResult; }
        private String llmObservation() { return llmObservation; }
        private Object structuredOutput() { return structuredOutput; }
        private List<Map<String, Object>> ledgerFacts() { return ledgerFacts; }
        private List<String> artifactRefs() { return artifactRefs; }
        private List<Map<String, Object>> artifactDescriptors() { return artifactDescriptors; }
    }
}
