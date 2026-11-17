package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.rich.RichTool;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/** Java Image Rich Tool：授权输入、消费私有 SSE、收口 Artifact 与 ReAct observation。 */
@Slf4j
public class ImageGenerationTool implements RichTool {

    public static final String TOOL_TYPE = "rich_image_generation";
    private static final int OBSERVATION_LIMIT = 2400;

    private final String name;
    private final String description;
    private final Object params;
    private final String pythonService;
    private final String pythonEndpoint;
    private final ImageGenerationExecutionKernel executionKernel;
    private final ImageGenerationInputArtifactResolver inputArtifactResolver;
    private final LedgerFactService ledgerFactService;
    private final ImageGenerationRuntimeFactService runtimeFactService;

    public ImageGenerationTool(
            String name,
            String description,
            Object params,
            String pythonService,
            String pythonEndpoint,
            ImageGenerationExecutionKernel executionKernel,
            ImageGenerationInputArtifactResolver inputArtifactResolver,
            LedgerFactService ledgerFactService,
            ImageGenerationRuntimeFactService runtimeFactService
    ) {
        this.name = name;
        this.description = description;
        this.params = params;
        this.pythonService = pythonService;
        this.pythonEndpoint = pythonEndpoint;
        this.executionKernel = executionKernel;
        this.inputArtifactResolver = inputArtifactResolver;
        this.ledgerFactService = ledgerFactService;
        this.runtimeFactService = runtimeFactService;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Object params() { return params; }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request)) {
            return failed(null, Instant.now(), "image_generation_request_invalid", "image_generation_tool 只接受受控工具调用请求。");
        }
        Instant startedAt = Instant.now();
        return executeRich(request, context,
                ledgerFactService.startToolInvocation(context, request, name, TOOL_TYPE), startedAt);
    }

    @Override
    public String richToolType() { return TOOL_TYPE; }

    /** 兼容既有 Rich Tool 定向测试与非批次调用入口。 */
    public ToolExecutionResult executeRich(ToolInvokeRequest request, AgentContext context) {
        return execute(request, context) instanceof ToolExecutionResult result ? result : failed(request, Instant.now(),
                "image_generation_execution_error", "图片生成未返回受控执行结果。");
    }

    @Override
    public ToolExecutionResult executeRich(ToolInvokeRequest request,
                                           AgentContext context,
                                           ToolInvocationLedgerEntity invocation,
                                           Instant startedAt) {
        ToolExecutionResult executionResult;
        try {
            ImageGenerationStartRequest startRequest = ImageGenerationStartRequest.from(request, invocation.getToolInvocationId());
            ImageGenerationInputArtifactResolver.ResolvedInputs resolved = inputArtifactResolver.resolve(context, startRequest);
            startRequest = startRequest.withResolvedInputs(resolved.references(), resolved.masks());
            runtimeFactService.start(invocation, startRequest);

            List<String> registeredArtifactIds = new ArrayList<>();
            ImageGenerationFinalResult result = executionKernel.execute(
                    pythonService,
                    pythonEndpoint,
                    startRequest,
                    event -> {
                        ImageGenerationRuntimeFactService.EventHandlingResult handled = runtimeFactService.accept(invocation, context, event);
                        if (!handled.duplicate() && StringUtils.hasText(handled.artifactId())) {
                            registeredArtifactIds.add(handled.artifactId());
                        }
                    }
            );
            executionResult = completed(request, startedAt, result, registeredArtifactIds, resolved.references().size(), resolved.masks().size());
            runtimeFactService.finish(invocation, result, executionResult.isSuccess(), executionResult.getErrorCode(), executionResult.getErrorMessage());
        } catch (ImageGenerationStreamException exception) {
            executionResult = failed(request, startedAt, exception.getErrorCode(), exception.getMessage());
            runtimeFactService.finish(invocation, null, false, exception.getErrorCode(), executionResult.getErrorMessage());
        } catch (RuntimeException exception) {
            log.error("image generation rich tool failed, toolCallId={}, requestId={}", request.getToolCallId(), request.getRequestId(), exception);
            executionResult = failed(request, startedAt, "image_generation_execution_error", "图片生成在获得可信终态前执行失败。");
            runtimeFactService.finish(invocation, null, false, executionResult.getErrorCode(), executionResult.getErrorMessage());
        }
        ToolInvocationLedgerEntity finished = ledgerFactService.finishToolInvocation(invocation, executionResult);
        log.info("image generation invocation finished, invocationId={}, status={}, success={}, durationMs={}, errorCode={}",
                finished.getToolInvocationId(), finished.getStatus(), executionResult.isSuccess(), executionResult.getDurationMs(), executionResult.getErrorCode());
        return executionResult;
    }

    private ToolExecutionResult completed(
            ToolInvokeRequest request,
            Instant startedAt,
            ImageGenerationFinalResult result,
            List<String> registeredArtifactIds,
            int referenceCount,
            int maskCount
    ) {
        if (result == null || !"completed".equals(result.getStatus())) {
            return failed(request, startedAt, result == null ? "image_generation_protocol_error" : firstNonBlank(result.getErrorCode(), "image_generation_failed"),
                    result == null ? "图片生成没有返回终态结果。" : result.getSummary());
        }
        List<String> finalArtifactIds = artifactIds(result);
        if (finalArtifactIds.isEmpty() || finalArtifactIds.size() != registeredArtifactIds.size() || !finalArtifactIds.equals(registeredArtifactIds)) {
            return failed(request, startedAt, "image_generation_contract_invalid", "图片生成成功终态未对应全部已登记的正式 Artifact。");
        }
        Instant finishedAt = Instant.now();
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", "completed");
        structured.put("mode", result.getMode());
        structured.put("requestedCount", result.getRequestedCount());
        structured.put("generatedCount", finalArtifactIds.size());
        structured.put("referenceArtifactCount", referenceCount);
        structured.put("maskArtifactCount", maskCount);
        structured.put("usedFallback", Boolean.TRUE.equals(result.getUsedFallback()));
        structured.put("artifactIds", List.copyOf(finalArtifactIds));
        structured.put("summary", limit(result.getSummary(), 1200));
        return ToolExecutionResult.builder()
                .toolName(name).toolCallId(request == null ? null : request.getToolCallId()).sourceType(TOOL_TYPE)
                .success(true).status("success").arguments(copyArguments(request)).metadata(copyMetadata(request))
                .outputText(limit(result.getSummary(), 1200))
                .llmObservation(successObservation(result, finalArtifactIds, referenceCount, maskCount))
                .structuredOutput(structured).ledgerFacts(List.of()).startedAt(startedAt).finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis()).artifactRefs(List.copyOf(finalArtifactIds))
                .attributes(new LinkedHashMap<>()).build();
    }

    private ToolExecutionResult failed(ToolInvokeRequest request, Instant startedAt, String errorCode, String errorMessage) {
        Instant finishedAt = Instant.now();
        String safeCode = firstNonBlank(errorCode, "image_generation_execution_error");
        String safeMessage = limit(firstNonBlank(errorMessage, "图片生成当前不可用。"), 1200);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", "failed"); structured.put("errorCode", safeCode); structured.put("errorMessage", safeMessage);
        return ToolExecutionResult.builder()
                .toolName(name).toolCallId(request == null ? null : request.getToolCallId()).sourceType(TOOL_TYPE)
                .success(false).status("failed").errorCode(safeCode).errorMessage(safeMessage)
                .arguments(copyArguments(request)).metadata(copyMetadata(request)).outputText(safeMessage)
                .llmObservation(failureObservation(safeCode, safeMessage)).structuredOutput(structured).ledgerFacts(List.of())
                .startedAt(startedAt).finishedAt(finishedAt).durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .artifactRefs(List.of()).attributes(new LinkedHashMap<>()).build();
    }

    private String successObservation(ImageGenerationFinalResult result, List<String> artifactIds, int referenceCount, int maskCount) {
        StringBuilder observation = new StringBuilder("图片已生成并登记为正式 Artifact。")
                .append("模式：").append(result.getMode()).append("；生成 ").append(artifactIds.size()).append(" 张。")
                .append("Artifact ID：").append(String.join("、", artifactIds)).append("；参考图 ").append(referenceCount).append(" 张；遮罩图 ").append(maskCount).append(" 张。")
                .append("摘要：").append(limit(result.getSummary(), 900)).append("。可通过 Artifact 预览、下载或在后续受控工具中继续使用。");
        if (Boolean.TRUE.equals(result.getUsedFallback())) observation.append("本次已自动切换兼容接口。");
        return limit(observation.toString(), OBSERVATION_LIMIT);
    }

    private String failureObservation(String errorCode, String message) {
        return limit("图片生成未能交付可信 Artifact（" + errorCode + "）。原因：" + message
                + "。不要假设图片已经产出；可检查输入 Artifact、调整生成指令或改用其他能力。", OBSERVATION_LIMIT);
    }

    private List<String> artifactIds(ImageGenerationFinalResult result) {
        if (result == null || result.getArtifacts() == null) return List.of();
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> artifact : result.getArtifacts()) {
            Object value = artifact == null ? null : artifact.get("artifactId");
            if (value == null || !StringUtils.hasText(String.valueOf(value))) return List.of();
            ids.add(String.valueOf(value));
        }
        return ids.size() == ids.stream().distinct().count() ? List.copyOf(ids) : List.of();
    }

    private Map<String, Object> copyArguments(ToolInvokeRequest request) {
        return request == null || request.getArguments() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getArguments());
    }

    private Map<String, Object> copyMetadata(ToolInvokeRequest request) {
        return request == null || request.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getMetadata());
    }

    private String firstNonBlank(String first, String second) { return StringUtils.hasText(first) ? first : second; }
    private String limit(String value, int maxLength) { return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength); }
}
