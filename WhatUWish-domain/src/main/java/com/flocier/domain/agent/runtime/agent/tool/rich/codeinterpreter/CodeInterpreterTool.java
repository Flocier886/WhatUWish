package com.flocier.domain.agent.runtime.agent.tool.rich.codeinterpreter;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolResponseEntity;
import com.flocier.domain.agent.runtime.agent.tool.rich.RichTool;
import com.flocier.domain.agent.runtime.artifact.WorkspaceArtifactDeliveryService;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import com.flocier.domain.agent.runtime.file.WorkspaceOutputPersistence;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressCommand;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** master 多步代码分析流程在本站的 Rich Tool 适配。 */
@Slf4j
public class CodeInterpreterTool implements RichTool {
    public static final String TOOL_TYPE = "rich_code_interpreter";
    private static final String NAME = "code_interpreter";
    private static final String DESCRIPTION = "委托独立代码分析 Agent 在当前会话的已授权输入上多步编写、执行和修正 Python。"
            + "适合 CSV/Excel 分析、图表和报告；内部过程会逐步返回。"
            + "读取 inputs/，将交付文件写入 outputs/；可用 sourceArtifactIds 指定 web_fetch 或 DeepSearch 来源 Artifact ID。"
            + "内部 Agent 完成分析和文件写入后，本工具才登记输出并返回 Artifact ID；最终向用户报告 ID 属于调用本工具的 Agent。";

    private final String pythonService;
    private final CodeInterpreterStreamClient streamClient;
    private final MaterialFileAccessResolver access;
    private final WorkspaceOutputPersistence outputs;
    private final WorkspaceArtifactDeliveryService delivery;
    private final LedgerFactService ledger;
    private final RichRuntimeProgressRecordingService progress;

    public CodeInterpreterTool(String pythonService, CodeInterpreterStreamClient streamClient,
            MaterialFileAccessResolver access, WorkspaceOutputPersistence outputs,
            WorkspaceArtifactDeliveryService delivery, LedgerFactService ledger,
            RichRuntimeProgressRecordingService progress) {
        this.pythonService = pythonService;
        this.streamClient = streamClient;
        this.access = access;
        this.outputs = outputs;
        this.delivery = delivery;
        this.ledger = ledger;
        this.progress = progress;
    }

    @Override public String name() { return NAME; }
    @Override public String description() { return DESCRIPTION; }
    @Override public String richToolType() { return TOOL_TYPE; }
    @Override public Object params() { return paramsDefinition(); }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request))
            return failed(null, Instant.now(), "code_interpreter_invalid_request", "代码分析调用参数无效");
        Instant startedAt = Instant.now();
        return executeRich(request, context, ledger.startToolInvocation(context, request, NAME, TOOL_TYPE), startedAt);
    }

    @Override
    public ToolExecutionResult executeRich(ToolInvokeRequest request, AgentContext context,
                                           ToolInvocationLedgerEntity invocation, Instant startedAt) {
        ToolExecutionResult result;
        try {
            if (invocation == null) throw new IllegalStateException("代码分析缺少可信工具调用");
            MaterialFileAccessResolver.Prepared prepared = access.prepare(context, NAME, request.getArguments());
            PythonToolResponseEntity response = streamClient.stream(pythonService, request, context, prepared,
                    event -> recordStep(invocation, context, event));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("代码分析已中断");
            if (response == null) {
                result = failed(request, startedAt, "code_interpreter_stream_failed", "代码分析未返回终态");
            } else if (!response.isSuccess()) {
                result = failed(request, startedAt, response.getErrorCode(), response.getErrorMessage());
            } else {
                if (response.getArtifacts() != null && !response.getArtifacts().isEmpty())
                    throw new IllegalStateException("代码分析服务返回了不受支持的直接产物");
                List<Map<String, Object>> descriptors = outputs.persist(response.getMaterialOutputs() == null
                        ? List.of() : response.getMaterialOutputs(), prepared.workspaceRoot(), NAME);
                if (Thread.currentThread().isInterrupted()) {
                    try { outputs.discard(descriptors); }
                    catch (Exception cleanup) { log.warn("interrupted code interpreter output cleanup failed", cleanup); }
                    throw new InterruptedException("代码分析已中断");
                }
                result = completed(request, startedAt, response, descriptors);
                delivery.register(context, request, result, invocation);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            result = failed(request, startedAt, "code_interpreter_interrupted", "代码分析已中断");
        } catch (IllegalArgumentException failure) {
            result = failed(request, startedAt, "material_access_denied", failure.getMessage());
        } catch (Exception failure) {
            log.error("code interpreter rich tool failed, toolCallId={}", request.getToolCallId(), failure);
            result = failed(request, startedAt, "material_output_failed", "代码分析输出持久化失败");
        }
        ledger.finishToolInvocation(invocation, result);
        return result;
    }

    private ToolExecutionResult completed(ToolInvokeRequest request, Instant startedAt,
                                          PythonToolResponseEntity response,
                                          List<Map<String, Object>> descriptors) {
        Instant finishedAt = Instant.now();
        return ToolExecutionResult.builder()
                .toolName(NAME).toolCallId(request.getToolCallId()).sourceType(TOOL_TYPE)
                .success(true).status(response.getStatus() == null ? "success" : response.getStatus())
                .arguments(arguments(request)).metadata(metadata(request))
                .outputText(response.getOutput()).llmObservation(response.getLlmObservation())
                .structuredOutput(response.getStructured() == null ? Map.of() : new LinkedHashMap<>(response.getStructured()))
                .ledgerFacts(response.getLedgerFacts() == null ? List.of() : response.getLedgerFacts())
                .artifactDescriptors(descriptors).artifactRefs(List.of())
                .startedAt(startedAt).finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .attributes(new LinkedHashMap<>()).build();
    }

    private ToolExecutionResult failed(ToolInvokeRequest request, Instant startedAt,
                                       String errorCode, String errorMessage) {
        Instant finishedAt = Instant.now();
        String code = errorCode == null || errorCode.isBlank() ? "code_interpreter_failed" : errorCode;
        String message = errorMessage == null || errorMessage.isBlank() ? "代码分析未完成" : errorMessage;
        return ToolExecutionResult.builder()
                .toolName(NAME).toolCallId(request == null ? null : request.getToolCallId()).sourceType(TOOL_TYPE)
                .success(false).status("failed").errorCode(code).errorMessage(message)
                .arguments(arguments(request)).metadata(metadata(request))
                .outputText(message).llmObservation("代码分析失败：" + message)
                .structuredOutput(Map.of("errorCode", code)).ledgerFacts(List.of())
                .artifactDescriptors(List.of()).artifactRefs(List.of())
                .startedAt(startedAt).finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .attributes(new LinkedHashMap<>()).build();
    }

    private Map<String, Object> arguments(ToolInvokeRequest request) {
        return request == null || request.getArguments() == null ? Map.of() : new LinkedHashMap<>(request.getArguments());
    }

    private Map<String, Object> metadata(ToolInvokeRequest request) {
        return request == null || request.getMetadata() == null ? Map.of() : new LinkedHashMap<>(request.getMetadata());
    }

    private void recordStep(ToolInvocationLedgerEntity active, AgentContext context, Map<String, Object> event) {
        boolean terminal = "result".equals(event.get("type"));
        boolean success = "success".equals(event.get("status"));
        String observation = text(event.get(terminal ? "conclusion" : "observation"), 4000);
        String message = terminal ? (success ? "代码分析结束，正在登记文件" : "代码分析失败")
                : "代码分析步骤 " + event.get("step");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", event.getOrDefault("step", event.getOrDefault("stepCount", 0)));
        data.put("thought", text(event.get("thought"), 4000));
        data.put("code", text(event.get("code"), 12000));
        data.put("observation", observation);
        data.put("status", terminal && !success ? "failed" : "running");
        progress.record(RichRuntimeProgressCommand.builder()
                .invocation(active).agentId(String.valueOf((Object) context.getAttribute("agentId")))
                .upstreamEventId(active.getToolInvocationId() + ":" + event.get("sequence"))
                .sequenceNo(((Number) event.get("sequence")).intValue())
                .eventType("code_interpreter_stage").stage(terminal ? (success ? "analysis_finished" : "failed") : "step")
                .status(terminal && !success ? "failed" : "running")
                .occurredAt(Instant.now()).message(message)
                .presentationKind("code_interpreter.step.v1").presentationSchemaVersion("1")
                .presentationData(data)
                .errorCode(terminal && !success ? text(event.get("errorCode"), 80) : null)
                .errorMessage(terminal && !success ? text(event.get("error"), 1200) : null)
                .build());
    }

    private static String text(Object value, int max) {
        String result = value == null ? "" : String.valueOf(value);
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static Map<String, Object> paramsDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", Map.of("type", "string", "description",
                "内部 Agent 要完成的分析、文件写入和结论任务；输出 Artifact ID 在本工具成功结束后自动登记并返回，不要求内部 Agent 预先取得"));
        properties.put("fileName", Map.of("type", "string", "description", "可选 Markdown 报告文件名，以 .md 结尾"));
        properties.put("sourceArtifactIds", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "可选：web_fetch 网页或 DeepSearch 来源包的 Artifact ID；分析 Agent 可读取物化后的 inputs/artifacts/ 文件"));
        return Map.of("type", "object", "properties", properties, "required", List.of("task"),
                "additionalProperties", false);
    }
}
