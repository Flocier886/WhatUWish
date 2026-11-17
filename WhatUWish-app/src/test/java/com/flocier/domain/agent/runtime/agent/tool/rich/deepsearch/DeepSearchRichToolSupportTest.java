package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import org.junit.Assert;
import org.junit.Test;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Rich Tool 的协议和 Tool Invocation 生命周期回归测试。 */
public class DeepSearchRichToolSupportTest {

    @Test
    public void shouldConsumeOrderedSseEventsAndReturnFinalResult() {
        HttpDeepSearchStreamClient client = new HttpDeepSearchStreamClient(HttpClient.newHttpClient());
        String progress = "event: progress\n"
                + "data: {\"schemaVersion\":\"deep_search_event.v1\",\"eventId\":\"evt-1\",\"executionId\":\"python-run\",\"sequence\":1,\"phase\":\"research_started\",\"status\":\"running\",\"occurredAt\":\"2026-07-30T10:00:00Z\",\"summary\":\"started\",\"data\":{}}\n";
        String artifact = "event: artifact\n"
                + "data: {\"schemaVersion\":\"deep_search_event.v1\",\"eventId\":\"evt-2\",\"executionId\":\"python-run\",\"sequence\":2,\"phase\":\"artifact_created\",\"status\":\"completed\",\"occurredAt\":\"2026-07-30T10:00:02Z\",\"summary\":\"artifact ready\",\"data\":{}}\n";
        String result = "event: result\n"
                + "data: {\"schemaVersion\":\"deep_search_result.v1\",\"executionId\":\"python-run\",\"researchExecutionId\":\"research-run\",\"status\":\"completed\",\"deliveryDisposition\":\"terminal_completed\",\"researchScopeFingerprint\":\"sha256:test\",\"deliveryMode\":\"model_synthesized\",\"summary\":\"research complete\",\"presentationSummary\":\"research complete\",\"sources\":[{}],\"evidence\":[{}],\"limitations\":[],\"stopReason\":\"coverage_sufficient\",\"stageSummaries\":[],\"artifacts\":[]}\n";

        java.util.ArrayList<DeepSearchStreamEvent> events = new java.util.ArrayList<>();
        // HttpClient 的 BodyHandlers.ofLines() 会逐行交给协议解析器；测试夹具也必须保持同一输入形态。
        DeepSearchFinalResult finalResult = client.consume(sseLines(progress, artifact, result), events::add);

        Assert.assertEquals("completed", finalResult.getStatus());
        Assert.assertEquals("research-run", finalResult.getResearchExecutionId());
        Assert.assertEquals(2, events.size());
        Assert.assertEquals("artifact", events.get(1).getStreamEventType());
    }

    @Test
    public void shouldRejectStreamThatEndsWithoutFinalResult() {
        HttpDeepSearchStreamClient client = new HttpDeepSearchStreamClient(HttpClient.newHttpClient());
        try {
            client.consume(Stream.of("event: progress", "data: {\"schemaVersion\":\"deep_search_event.v1\"}", ""), ignored -> { });
            Assert.fail("stream without result must fail");
        } catch (DeepSearchStreamException exception) {
            Assert.assertTrue(exception.getErrorCode().startsWith("deep_search_"));
        }
    }

    @Test
    public void shouldTranslateControlledPythonValidationErrorWithoutEchoingTheResponseBody() {
        HttpDeepSearchStreamClient client = new HttpDeepSearchStreamClient(HttpClient.newHttpClient());

        DeepSearchStreamException exception = client.parseHttpError(422,
                "{\"errorCode\":\"deep_search_request_invalid\",\"message\":\"ignored\","
                        + "\"violations\":[{\"field\":\"preferredDomains\",\"rule\":\"too_long\",\"limit\":8}],"
                        + "\"untrusted\":\"must not be exposed\"}");

        Assert.assertEquals("deep_search_request_invalid", exception.getErrorCode());
        Assert.assertTrue(exception.getMessage().contains("preferredDomains"));
        Assert.assertTrue(exception.getMessage().contains("8"));
        Assert.assertFalse(exception.getMessage().contains("must not be exposed"));
    }

    @Test
    public void shouldKeepUnknownHttpErrorBodiesOutOfTheAgentObservation() {
        HttpDeepSearchStreamClient client = new HttpDeepSearchStreamClient(HttpClient.newHttpClient());

        DeepSearchStreamException exception = client.parseHttpError(502, "upstream secret response");

        Assert.assertEquals("deep_search_http_error", exception.getErrorCode());
        Assert.assertEquals("DeepSearch service returned HTTP 502", exception.getMessage());
    }

    @Test
    public void shouldKeepInternalQueriesAndWorkflowBudgetsOutOfTheJavaRichToolRequest() {
        ToolInvokeRequest request = ToolInvokeRequest.builder()
                .toolName("deep_search_tool")
                .toolCallId("call-1")
                .arguments(Map.of(
                        "topic", "Tavily API documentation",
                        "questions", List.of("How is the API authenticated?"),
                        "queries", List.of("Tavily API authentication endpoint"),
                        "maxSearches", 1,
                        "maxResultsPerSearch", 1,
                        "maxFetches", 0,
                        "maxIterations", 1
                ))
                .build();

        DeepSearchStartRequest startRequest = DeepSearchStartRequest.from(request, "tool-invocation-1");

        Assert.assertEquals("Tavily API documentation", startRequest.getPayload().get("topic"));
        Assert.assertTrue(startRequest.getPayload().containsKey("questions"));
        Assert.assertFalse(startRequest.getPayload().containsKey("queries"));
        Assert.assertFalse(startRequest.getPayload().containsKey("maxSearches"));
        Assert.assertFalse(startRequest.getPayload().containsKey("maxResultsPerSearch"));
        Assert.assertFalse(startRequest.getPayload().containsKey("maxFetches"));
        Assert.assertFalse(startRequest.getPayload().containsKey("maxIterations"));
        Assert.assertEquals("java-tool-invocation:tool-invocation-1", startRequest.getPayload().get("executionId"));
    }

    @Test
    public void shouldPersistOneRunningInvocationAndUpdateTheSameFactAtCompletion() {
        InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        LedgerRecordingService service = new LedgerRecordingService(
                new InMemorySessionLedgerRepository(), new InMemoryRunLedgerRepository(),
                new InMemoryLlmInvocationLedgerRepository(), tools, new InMemoryArtifactLedgerRepository());
        AgentContext context = AgentContext.builder().sessionId("session-1").requestId("request-1").traceId("trace-1").currentStep(2).build();
        context.setAttribute("runId", "run-1");
        context.setAttribute("agentId", "react-agent");
        ToolInvokeRequest request = ToolInvokeRequest.builder().toolName("deep_search_tool").toolCallId("call-1")
                .requestId("request-1").sessionId("session-1").step(2).arguments(Map.of("topic", "ReAct observation")).build();

        ToolInvocationLedgerEntity invocation = service.startToolInvocation(context, request, "deep_search_tool", "rich_python");
        Assert.assertEquals("RUNNING", invocation.getStatus());
        Assert.assertEquals("rich_tool", invocation.getExt().get("lifecycle"));
        Assert.assertEquals(1, tools.findByRunId("run-1").size());

        ToolExecutionResult result = ToolExecutionResult.builder().toolName("deep_search_tool").toolCallId("call-1")
                .sourceType("rich_python").success(true).status("success").llmObservation("research complete")
                .structuredOutput(Map.of("status", "completed")).startedAt(invocation.getStartedAt())
                .finishedAt(Instant.now()).durationMs(25L).artifactRefs(List.of("artifact-1")).build();
        ToolInvocationLedgerEntity finished = service.finishToolInvocation(invocation, result);

        Assert.assertEquals(invocation.getToolInvocationId(), finished.getToolInvocationId());
        Assert.assertEquals("success", finished.getStatus());
        Assert.assertNotNull(finished.getFinishedAt());
        Assert.assertEquals("artifact-1", finished.getArtifactRef());
        Assert.assertEquals(1, tools.findByRunId("run-1").size());
    }

    @Test
    public void shouldTreatAFailedPythonFinalResultAsAFailedToolObservation() {
        InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        LedgerRecordingService recordingService = recordingService(tools);
        LedgerFactService ledgerFactService = new LedgerFactService(recordingService, null);
        DeepSearchFinalResult pythonResult = finalResult("failed", "证据不足，无法得出可靠结论", "insufficient_evidence");
        DeepSearchStreamClient client = (service, endpoint, request, consumer) -> pythonResult;
        DeepSearchTool tool = new DeepSearchTool("deep_search_tool", "deep search", Map.of(), "http://unused", "/unused", client, ledgerFactService);

        ToolExecutionResult result = tool.executeRich(request(), context());

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("failed", result.getStatus());
        Assert.assertTrue(result.getLlmObservation().contains("未产出可直接作为研究结论使用的结果"));
        Assert.assertEquals("failed", tools.findByRunId("run-1").get(0).getStatus());
    }

    @Test
    public void shouldAdvanceFromLimitedEvidenceArtifactInsteadOfRetryingTheSameResearch() {
        InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        LedgerFactService ledgerFactService = new LedgerFactService(recordingService(tools), null);
        DeepSearchFinalResult pythonResult = finalResult("completed", "已有可引用证据包，但覆盖存在限制", "iteration_limit_reached");
        pythonResult.setDeliveryDisposition("terminal_completed");
        pythonResult.setDeliveryMode("evidence_only");
        pythonResult.setResearchOutcome("evidence_limited");
        DeepSearchStreamClient client = (service, endpoint, request, consumer) -> pythonResult;
        DeepSearchTool tool = new DeepSearchTool("deep_search_tool", "deep search", Map.of(), "http://unused", "/unused", client, ledgerFactService);

        ToolExecutionResult result = tool.executeRich(request(), context());

        Assert.assertTrue(result.isSuccess());
        Assert.assertTrue(result.getLlmObservation().contains("当前研究范围已完成并交付来源材料包"));
        Assert.assertTrue(result.getLlmObservation().contains("已交付可用来源材料"));
        Assert.assertTrue(result.getLlmObservation().contains("不要重调相同研究范围"));
        Assert.assertTrue(result.getLlmObservation().contains("后续报告交付能力"));
        Assert.assertTrue(result.getLlmObservation().contains("直接调用 report_tool"));
        Assert.assertTrue(result.getLlmObservation().contains("Artifact ID：artifact-1"));
        Assert.assertTrue(result.getLlmObservation().contains("sourceArtifactIds"));
        Assert.assertTrue(result.getLlmObservation().contains("inputs/artifacts/<Artifact ID>-deepsearch-source-package.md"));
        Assert.assertFalse(result.getLlmObservation().contains("inputArtifactIds"));
        Assert.assertFalse(result.getLlmObservation().contains("artifact_read"));
        Assert.assertFalse(result.getLlmObservation().contains("缩小范围"));
    }

    @Test
    public void shouldSeparateFetchedSourceCountFromCandidateSourceCountInObservation() {
        InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        LedgerFactService ledgerFactService = new LedgerFactService(recordingService(tools), null);
        DeepSearchFinalResult pythonResult = finalResult("completed", "已形成研究摘要", "research_completed");
        pythonResult.setSources(List.of(
                Map.of("sourceId", "candidate-1", "status", "fetched"),
                Map.of("sourceId", "candidate-2", "status", "fetch_failed"),
                Map.of("sourceId", "candidate-3", "status", "candidate")
        ));
        DeepSearchStreamClient client = (service, endpoint, request, consumer) -> pythonResult;
        DeepSearchTool tool = new DeepSearchTool("deep_search_tool", "deep search", Map.of(), "http://unused", "/unused", client, ledgerFactService);

        ToolExecutionResult result = tool.executeRich(request(), context());

        Assert.assertTrue(result.isSuccess());
        Assert.assertTrue(result.getLlmObservation().contains("来源候选数：3；成功抓取来源数：1"));
        Assert.assertFalse(result.getLlmObservation().contains("已交付来源数：3"));
    }

    @Test
    public void shouldRejectATerminalDeliveryWithoutAnArtifactReference() {
        InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        LedgerFactService ledgerFactService = new LedgerFactService(recordingService(tools), null);
        DeepSearchFinalResult pythonResult = finalResult("completed", "研究摘要", "coverage_sufficient");
        pythonResult.setArtifacts(List.of());
        DeepSearchStreamClient client = (service, endpoint, request, consumer) -> pythonResult;
        DeepSearchTool tool = new DeepSearchTool("deep_search_tool", "deep search", Map.of(), "http://unused", "/unused", client, ledgerFactService);

        ToolExecutionResult result = tool.executeRich(request(), context());

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("deep_search_contract_invalid", result.getErrorCode());
        Assert.assertTrue(result.getLlmObservation().contains("不符合 Rich Tool 交付契约"));
    }

    @Test
    public void shouldBoundDeepSearchObservationWithoutPuttingReportTextInTheLedgerResult() {
        InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        LedgerFactService ledgerFactService = new LedgerFactService(recordingService(tools), null);
        DeepSearchFinalResult pythonResult = finalResult("completed", "摘要-" + "x".repeat(6000), "coverage_sufficient");
        DeepSearchStreamClient client = (service, endpoint, request, consumer) -> pythonResult;
        DeepSearchTool tool = new DeepSearchTool("deep_search_tool", "deep search", Map.of(), "http://unused", "/unused", client, ledgerFactService);

        ToolExecutionResult result = tool.executeRich(request(), context());

        Assert.assertTrue(result.isSuccess());
        Assert.assertTrue(result.getLlmObservation().length() <= 3200);
        Assert.assertFalse(result.getStructuredOutput().toString().contains("x".repeat(6000)));
        Assert.assertEquals(1, tools.findByRunId("run-1").size());
        String payload = tools.findByRunId("run-1").get(0).getResponsePayloadJson();
        Assert.assertTrue(payload.contains("\"schemaVersion\":2"));
        Assert.assertFalse(payload.contains("\"rawPayload\":"));
        Assert.assertFalse(payload.contains("x".repeat(6000)));
    }

    @Test
    public void shouldPreventSameScopeTerminalDeepSearchWithinTheCurrentRun() {
        InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        LedgerFactService ledgerFactService = new LedgerFactService(recordingService(tools), null);
        AgentContext context = context();
        String fingerprint = DeepSearchScopeFingerprint.fromPayload(DeepSearchStartRequest.from(request(), "previous-invocation").getPayload());
        ToolExecutionResult previous = ToolExecutionResult.builder()
                .toolName("deep_search_tool").toolCallId("previous-call").success(true).status("success")
                .outputText("已有研究结论")
                .llmObservation("此前 DeepSearch 已终结。")
                .structuredOutput(Map.of("deliveryDisposition", "terminal_partial", "researchScopeFingerprint", fingerprint, "summary", "已有研究结论"))
                .artifactRefs(List.of("artifact-1")).build();
        context.appendToolExecutionResult(previous);
        DeepSearchStreamClient client = (service, endpoint, startRequest, consumer) -> {
            Assert.fail("相同终态研究范围不应再次连接 Python 服务");
            return null;
        };
        DeepSearchTool tool = new DeepSearchTool("deep_search_tool", "deep search", Map.of(), "http://unused", "/unused", client, ledgerFactService);

        ToolExecutionResult duplicate = tool.executeRich(request(), context);

        Assert.assertTrue(duplicate.isSuccess());
        Assert.assertTrue(duplicate.getLlmObservation().contains("已在当前 Run 中完成相同研究范围"));
        Assert.assertEquals(Boolean.TRUE, ((Map<?, ?>) duplicate.getStructuredOutput()).get("duplicateScopePrevented"));
        Assert.assertEquals(1, duplicate.getArtifactRefs().size());
    }

    @Test
    public void shouldNotPublishOrReturnAResultWhenTheFinalLedgerUpdateFails() {
        FailingUpdateToolInvocationRepository tools = new FailingUpdateToolInvocationRepository();
        LedgerRecordingService recordingService = recordingService(tools);
        List<RuntimeEventType> publishedEventTypes = new ArrayList<>();
        LedgerFactService ledgerFactService = new LedgerFactService(
                recordingService, null, new RuntimeEventMapper(), draft -> publishedEventTypes.add(draft.getEventType()));
        DeepSearchFinalResult pythonResult = finalResult("completed", "研究完成", "coverage_sufficient");
        DeepSearchStreamClient client = (service, endpoint, request, consumer) -> pythonResult;
        DeepSearchTool tool = new DeepSearchTool("deep_search_tool", "deep search", Map.of(), "http://unused", "/unused", client, ledgerFactService);

        try {
            tool.executeRich(request(), context());
            Assert.fail("未持久化的最终结果不得返回给 ReAct");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("simulated ledger update failure"));
        }
        Assert.assertEquals(List.of(RuntimeEventType.TOOL_STARTED), publishedEventTypes);
        Assert.assertEquals("RUNNING", tools.findByRunId("run-1").get(0).getStatus());
        Assert.assertNull(tools.findByRunId("run-1").get(0).getFinishedAt());
    }

    private Stream<String> sseLines(String... frames) {
        return Arrays.stream(frames)
                .flatMap(frame -> Arrays.stream((frame + "\n").split("\\n", -1)));
    }

    private LedgerRecordingService recordingService(InMemoryToolInvocationLedgerRepository tools) {
        return new LedgerRecordingService(
                new InMemorySessionLedgerRepository(), new InMemoryRunLedgerRepository(),
                new InMemoryLlmInvocationLedgerRepository(), tools, new InMemoryArtifactLedgerRepository());
    }

    private AgentContext context() {
        AgentContext context = AgentContext.builder().sessionId("session-1").requestId("request-1").traceId("trace-1").currentStep(2).build();
        context.setAttribute("runId", "run-1");
        context.setAttribute("agentId", "react-agent");
        return context;
    }

    private ToolInvokeRequest request() {
        return ToolInvokeRequest.builder().toolName("deep_search_tool").toolCallId("call-1")
                .requestId("request-1").sessionId("session-1").step(2).arguments(Map.of("topic", "ReAct observation")).build();
    }

    private DeepSearchFinalResult finalResult(String status, String summary, String stopReason) {
        DeepSearchFinalResult result = new DeepSearchFinalResult();
        result.setSchemaVersion("deep_search_result.v1");
        result.setExecutionId("python-run");
        result.setResearchExecutionId("research-run");
        result.setStatus(status);
        result.setDeliveryDisposition("failed".equals(status) ? "failed_unrecoverable" : "terminal_completed");
        result.setResearchOutcome("evidence_sufficient");
        result.setCallerAction("none");
        result.setResearchScopeFingerprint("sha256:test");
        result.setDeliveryMode("model_synthesized");
        result.setSummary(summary);
        result.setPresentationSummary(summary);
        result.setStopReason(stopReason);
        if (!"failed".equals(status)) {
            ArtifactRuntimeDescriptor artifact = new ArtifactRuntimeDescriptor();
            artifact.setArtifactId("artifact-1");
            artifact.setArtifactType("research_evidence_package");
            artifact.setName("Research Evidence Package");
            artifact.setFileName("research-evidence-package.md");
            artifact.setContentSummary("可引用研究证据包");
            artifact.setMimeType("text/markdown");
            artifact.setFileSize(128L);
            result.setArtifacts(List.of(artifact));
        }
        return result;
    }

    /** 模拟数据库终态更新失败，用于保证不会产生“未落账但已通知”的最终事件。 */
    private static class FailingUpdateToolInvocationRepository extends InMemoryToolInvocationLedgerRepository {
        @Override
        public void update(ToolInvocationLedgerEntity entity) {
            throw new IllegalStateException("simulated ledger update failure");
        }
    }
}
