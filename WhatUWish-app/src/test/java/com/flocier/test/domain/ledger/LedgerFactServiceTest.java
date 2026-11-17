package com.flocier.test.domain.ledger;

import com.flocier.application.agent.execute.react.ReactAgentExecuteStrategy;
import com.flocier.application.agent.execute.workflow.WorkflowAgentExecuteStrategy;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.response.LlmUsage;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.ledger.model.entity.LedgerFactEntity;
import com.flocier.domain.ledger.model.entity.LedgerFactQueryEntity;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;
import com.flocier.domain.ledger.model.vo.FactType;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.LedgerQueryService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LedgerFactServiceTest {

    private LedgerFactService newService() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );
        LedgerQueryService queryService = new LedgerQueryService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );
        return new LedgerFactService(recordingService, queryService);
    }

    @Test
    public void shouldRecordToolFactAndQueryByTrace() {
        LedgerFactService service = newService();
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .sessionId("session-1")
                .traceId("trace-1")
                .currentStep(2)
                .build();
        context.setAttribute("runId", "run-1");
        service.recordRunStarted(context, "agent-1", "test");
        ToolInvokeRequest request = ToolInvokeRequest.builder()
                .toolName("echo_tool")
                .toolCallId("call-1")
                .arguments(Map.of("message", "hello"))
                .requestId("req-1")
                .sessionId("session-1")
                .step(2)
                .build();
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("echo_tool")
                .toolCallId("call-1")
                .sourceType("java")
                .success(true)
                .status("success")
                .outputText("hello")
                .finishedAt(Instant.now())
                .durationMs(12L)
                .build();

        service.recordToolCall(context, request, result);

        List<LedgerFactEntity> facts = service.query(LedgerFactQueryEntity.builder()
                .traceId("trace-1")
                .factType(FactType.TOOL_CALL)
                .build());

        Assert.assertEquals(1, facts.size());
        Assert.assertEquals(FactType.TOOL_CALL, facts.get(0).getFactType());
        Assert.assertEquals("echo_tool", facts.get(0).getComponentCode());
        Assert.assertEquals("session-1", facts.get(0).getSessionId());
    }

    @Test
    public void shouldRecordLlmFactWithUsage() {
        LedgerFactService service = newService();
        LlmRequest request = LlmRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(LlmMessage.builder()
                        .role(LlmMessageRole.USER)
                        .content("hello")
                        .build()))
                .attributes(Map.of(
                        "requestId", "req-2",
                        "sessionId", "session-2",
                        "traceId", "trace-2",
                        "scene", "summary",
                        "step", 3
                ))
                .build();
        LlmResponse response = LlmResponse.builder()
                .model("gpt-4o-mini")
                .provider("default-openai")
                .text("hi")
                .finishReason("stop")
                .usage(LlmUsage.builder().promptTokens(10).completionTokens(12).totalTokens(22).build())
                .build();

        service.recordLlmCall(request, response);

        List<LedgerFactEntity> facts = service.query(LedgerFactQueryEntity.builder()
                .traceId("trace-2")
                .factType(FactType.LLM_CALL)
                .build());

        Assert.assertEquals(1, facts.size());
        Assert.assertEquals(Long.valueOf(22), facts.get(0).getUsageQuantity());
        Assert.assertEquals("default-openai", facts.get(0).getProviderCode());
        Assert.assertEquals("gpt-4o-mini", facts.get(0).getComponentCode());
    }

    @Test
    public void shouldNotCreateArtifactForPlainLlmCall() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );

        LlmRequest request = LlmRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(LlmMessage.builder()
                        .role(LlmMessageRole.USER)
                        .content("hello")
                        .build()))
                .attributes(Map.of(
                        "requestId", "req-plain-llm",
                        "sessionId", "session-plain-llm",
                        "traceId", "trace-plain-llm",
                        "runId", "run-plain-llm",
                        "scene", "summary",
                        "step", 1
                ))
                .build();
        LlmResponse response = LlmResponse.builder()
                .model("gpt-4o-mini")
                .provider("default-openai")
                .text("plain llm response")
                .finishReason("stop")
                .usage(LlmUsage.builder().promptTokens(1).completionTokens(2).totalTokens(3).build())
                .build();

        recordingService.recordLlmCall(request, response);

        Assert.assertEquals(1, llmRepository.findByRunId("run-plain-llm").size());
        Assert.assertTrue(artifactRepository.findByRunId("run-plain-llm").isEmpty());
    }

    @Test
    public void shouldKeepMultipleToolAskRecordsWithinSameStepWhenLlmCallIdDiffers() {
        LedgerFactService service = newService();

        ToolAskRequest firstRequest = ToolAskRequest.builder()
                .model("gpt-4o-mini")
                .attributes(Map.of(
                        "requestId", "req-tool-ask",
                        "sessionId", "session-tool-ask",
                        "traceId", "trace-tool-ask",
                        "runId", "run-tool-ask",
                        "scene", "react_tool_ask",
                        "step", 2,
                        "llmCallId", "tool-ask-call-1"
                ))
                .build();
        ToolAskRequest secondRequest = ToolAskRequest.builder()
                .model("gpt-4o-mini")
                .attributes(Map.of(
                        "requestId", "req-tool-ask",
                        "sessionId", "session-tool-ask",
                        "traceId", "trace-tool-ask",
                        "runId", "run-tool-ask",
                        "scene", "react_tool_ask",
                        "step", 2,
                        "llmCallId", "tool-ask-call-2"
                ))
                .build();
        ToolAskResponse response = ToolAskResponse.builder()
                .model("gpt-4o-mini")
                .provider("default-openai")
                .thought("继续执行")
                .build();

        service.recordToolAsk(firstRequest, response);
        service.recordToolAsk(secondRequest, response);

        List<LedgerFactEntity> facts = service.query(LedgerFactQueryEntity.builder()
                .traceId("trace-tool-ask")
                .factType(FactType.LLM_CALL)
                .build());

        Assert.assertEquals(2, facts.size());
        Assert.assertTrue(String.valueOf(facts.get(0).getExt()).contains("llmCallId"));
        Assert.assertTrue(String.valueOf(facts.get(1).getExt()).contains("llmCallId"));
    }

    @Test
    public void shouldBuildRunViewWithArtifacts() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );
        LedgerQueryService queryService = new LedgerQueryService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );

        AgentContext context = AgentContext.builder()
                .requestId("req-3")
                .sessionId("session-3")
                .traceId("trace-3")
                .currentStep(1)
                .result("final result")
                .build();
        context.setAttribute("runId", "run-3");
        recordingService.recordRunStarted(context, "agent-3", "workflow");
        recordingService.recordRunFinished(context, "COMPLETED", "done", null, null);

        ToolInvokeRequest request = ToolInvokeRequest.builder()
                .toolName("file_tool")
                .toolCallId("call-3")
                .arguments(Map.of("path", "/tmp/a.txt"))
                .requestId("req-3")
                .sessionId("session-3")
                .step(1)
                .build();
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("file_tool")
                .toolCallId("call-3")
                .sourceType("java")
                .success(true)
                .status("success")
                .outputText("created")
                .finishedAt(Instant.now())
                .durationMs(20L)
                .build();
        recordingService.recordToolCall(context, request, result);
        recordingService.recordArtifact(ArtifactLedgerEntity.builder()
                .artifactId("artifact-run-3-file")
                .runId("run-3")
                .sessionId("session-3")
                .requestId("req-3")
                .traceId("trace-3")
                .sourceType("tool_output")
                .sourceId("call-3")
                .artifactType("file")
                .artifactName("a.txt")
                .storageType("file_ref")
                .storageRef("artifacts/run-3/a.txt")
                .contentSummary("created file")
                .mimeType("text/plain")
                .size(12L)
                .idempotencyKey("artifact:file:run-3:call-3:a.txt")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        LedgerRunView runView = queryService.queryRunView("run-3").orElse(null);
        Assert.assertNotNull(runView);
        Assert.assertNotNull(runView.getSession());
        Assert.assertNotNull(runView.getRun());
        Assert.assertEquals("run-3", runView.getRun().getRunId());
        Assert.assertEquals("session-3", runView.getSession().getSessionId());
        Assert.assertEquals(1, runView.getToolInvocations().size());
        Assert.assertEquals(1, runView.getArtifacts().size());
    }

    @Test
    public void shouldKeepToolOwnedProtocolFieldsInToolLedgerExt() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );
        LedgerQueryService queryService = new LedgerQueryService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );

        AgentContext context = AgentContext.builder()
                .requestId("req-4")
                .sessionId("session-4")
                .traceId("trace-4")
                .currentStep(2)
                .build();
        context.setAttribute("runId", "run-4");
        recordingService.recordRunStarted(context, "agent-4", "react");

        ToolInvokeRequest request = ToolInvokeRequest.builder()
                .toolName("file_read")
                .toolCallId("call-4")
                .arguments(Map.of("path", "missing.txt"))
                .requestId("req-4")
                .sessionId("session-4")
                .step(2)
                .build();
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("file_read")
                .toolCallId("call-4")
                .sourceType("python")
                .success(false)
                .status("failed")
                .errorCode("file_not_found")
                .errorMessage("File not found: missing.txt")
                .outputText("file read failed")
                .llmObservation("文件读取失败：目标文件不存在。建议先调用 directory_list 或 file_search 确认真实路径。")
                .structuredOutput(Map.of(
                        "path", "missing.txt",
                        "matches", List.of()
                ))
                .ledgerFacts(List.of(Map.of(
                        "factType", "file_read_failed",
                        "failureType", "not_found"
                )))
                .artifactRefs(List.of("artifacts/missing-debug.txt"))
                .finishedAt(Instant.now())
                .durationMs(18L)
                .build();

        recordingService.recordToolCall(context, request, result);

        LedgerRunView runView = queryService.queryRunView("run-4").orElse(null);
        Assert.assertNotNull(runView);
        Assert.assertEquals(1, runView.getToolInvocations().size());
        Assert.assertTrue(runView.getArtifacts().isEmpty());
        Assert.assertEquals("artifacts/missing-debug.txt", runView.getToolInvocations().get(0).getArtifactRef());
        var invocation = runView.getToolInvocations().get(0);
        Assert.assertTrue(invocation.getExt().isEmpty());
        Assert.assertTrue(invocation.getResponsePayloadJson().contains("文件读取失败：目标文件不存在。建议先调用 directory_list 或 file_search 确认真实路径。"));
        Assert.assertTrue(invocation.getResponsePayloadJson().contains("missing.txt"));
        Assert.assertFalse(invocation.getResponsePayloadJson().contains("\"ledgerFacts\":"));
        Assert.assertTrue(invocation.getResponsePayloadJson().contains("artifacts/missing-debug.txt"));
    }

    @Test
    public void shouldAcceptReusableArtifactAndRejectLegacyPseudoArtifact() {
        InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                new InMemorySessionLedgerRepository(),
                new InMemoryRunLedgerRepository(),
                new InMemoryLlmInvocationLedgerRepository(),
                new InMemoryToolInvocationLedgerRepository(),
                artifactRepository
        );
        Instant now = Instant.now();

        recordingService.recordArtifact(ArtifactLedgerEntity.builder()
                .artifactId("artifact-real-file")
                .runId("run-artifact-guard")
                .sessionId("session-artifact-guard")
                .requestId("req-artifact-guard")
                .traceId("trace-artifact-guard")
                .sourceType("python_artifact")
                .sourceId("python-call-1")
                .artifactType("file")
                .artifactName("report.md")
                .storageType("file_ref")
                .storageRef("artifacts/run-artifact-guard/report.md")
                .contentSummary("generated report")
                .mimeType("text/markdown")
                .idempotencyKey("artifact:file:run-artifact-guard:report.md")
                .createdAt(now)
                .updatedAt(now)
                .build());
        recordingService.recordArtifact(ArtifactLedgerEntity.builder()
                .artifactId("artifact-pseudo-tool")
                .runId("run-artifact-guard")
                .sessionId("session-artifact-guard")
                .requestId("req-artifact-guard")
                .traceId("trace-artifact-guard")
                .sourceType("tool")
                .sourceId("tool-call-1")
                .artifactType("tool_result")
                .artifactName("echo_tool")
                .storageType("inline_summary")
                .storageRef("echo_tool")
                .contentSummary("plain tool observation")
                .mimeType("text/plain")
                .idempotencyKey("artifact:pseudo:tool")
                .createdAt(now)
                .updatedAt(now)
                .build());

        Assert.assertEquals(1, artifactRepository.findByRunId("run-artifact-guard").size());
        Assert.assertEquals("file", artifactRepository.findByRunId("run-artifact-guard").get(0).getArtifactType());
    }

    @Test
    public void shouldQueryRunByRequestIdAndKeepRunIdSeparatedFromSessionId() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );
        LedgerQueryService queryService = new LedgerQueryService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );

        AgentContext context = AgentContext.builder()
                .requestId("req-separated")
                .sessionId("session-shared")
                .traceId("trace-separated")
                .build();
        context.setAttribute("runId", "run-separated");

        recordingService.recordRunStarted(context, "agent-separated", "workflow");
        recordingService.recordRunFinished(context, "COMPLETED", "done", null, null);

        LedgerRunView runView = queryService.queryRunView("run-separated").orElse(null);
        Assert.assertNotNull(runView);
        Assert.assertEquals("run-separated", runView.getRun().getRunId());
        Assert.assertEquals("session-shared", runView.getRun().getSessionId());
        Assert.assertEquals("req-separated", runView.getRun().getRequestId());
        Assert.assertFalse(runView.getRun().getSessionId().equals(runView.getRun().getRunId()));

        Assert.assertEquals(
                1,
                queryService.queryRuns(com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity.builder()
                        .requestId("req-separated")
                        .build()).size()
        );
        Assert.assertEquals(
                "run-separated",
                queryService.queryRuns(com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity.builder()
                                .requestId("req-separated")
                                .build())
                        .get(0)
                        .getRunId()
        );
    }

    @Test
    public void shouldKeepSessionCountsIdempotentWhenRunFinishRepeated() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                new InMemoryLlmInvocationLedgerRepository(),
                new InMemoryToolInvocationLedgerRepository(),
                new InMemoryArtifactLedgerRepository()
        );

        AgentContext context = AgentContext.builder()
                .requestId("req-finish-idempotent")
                .sessionId("session-finish-idempotent")
                .traceId("trace-finish-idempotent")
                .build();
        context.setAttribute("runId", "run-finish-idempotent");

        recordingService.recordRunStarted(context, "agent-finish", "react_workflow");
        recordingService.recordRunFinished(context, "COMPLETED", "done once", null, null);
        recordingService.recordRunFinished(context, "COMPLETED", "done twice", null, null);

        SessionLedgerEntity session = sessionRepository.findBySessionId("session-finish-idempotent").orElse(null);
        Assert.assertNotNull(session);
        Assert.assertEquals(Integer.valueOf(1), session.getRunCount());
        Assert.assertEquals(Integer.valueOf(1), session.getFinishedRunCount());
        Assert.assertEquals(Integer.valueOf(0), session.getFailedRunCount());
        Assert.assertEquals("done twice", session.getLatestSummaryText());
    }

    @Test
    public void shouldRecalculateSessionCountsWhenTerminalStatusChanges() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                new InMemoryLlmInvocationLedgerRepository(),
                new InMemoryToolInvocationLedgerRepository(),
                new InMemoryArtifactLedgerRepository()
        );

        AgentContext context = AgentContext.builder()
                .requestId("req-finish-transition")
                .sessionId("session-finish-transition")
                .traceId("trace-finish-transition")
                .build();
        context.setAttribute("runId", "run-finish-transition");

        recordingService.recordRunStarted(context, "agent-finish", "react_workflow");
        recordingService.recordRunFinished(context, "COMPLETED", "first completed", null, null);
        recordingService.recordRunFinished(context, "FAILED", "then failed", "test_failure", "changed terminal state");

        SessionLedgerEntity session = sessionRepository.findBySessionId("session-finish-transition").orElse(null);
        Assert.assertNotNull(session);
        Assert.assertEquals(Integer.valueOf(1), session.getRunCount());
        Assert.assertEquals(Integer.valueOf(0), session.getFinishedRunCount());
        Assert.assertEquals(Integer.valueOf(1), session.getFailedRunCount());
        Assert.assertEquals("FAILED", session.getStatus());
    }

    @Test
    public void shouldKeepSessionCountsIdempotentWhenFailedRunFinishRepeated() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                new InMemoryLlmInvocationLedgerRepository(),
                new InMemoryToolInvocationLedgerRepository(),
                new InMemoryArtifactLedgerRepository()
        );

        AgentContext context = AgentContext.builder()
                .requestId("req-failed-idempotent")
                .sessionId("session-failed-idempotent")
                .traceId("trace-failed-idempotent")
                .build();
        context.setAttribute("runId", "run-failed-idempotent");

        recordingService.recordRunStarted(context, "agent-finish", "react_workflow");
        recordingService.recordRunFinished(context, "FAILED", "failed once", "test_failure", "first failure");
        recordingService.recordRunFinished(context, "FAILED", "failed twice", "test_failure", "second failure");

        SessionLedgerEntity session = sessionRepository.findBySessionId("session-failed-idempotent").orElse(null);
        Assert.assertNotNull(session);
        Assert.assertEquals(Integer.valueOf(1), session.getRunCount());
        Assert.assertEquals(Integer.valueOf(0), session.getFinishedRunCount());
        Assert.assertEquals(Integer.valueOf(1), session.getFailedRunCount());
        Assert.assertEquals("FAILED", session.getStatus());
        Assert.assertEquals("failed twice", session.getLatestSummaryText());
    }

    @Test
    public void shouldRecalculateSessionCountsWhenFailedRunChangesToCompleted() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                new InMemoryLlmInvocationLedgerRepository(),
                new InMemoryToolInvocationLedgerRepository(),
                new InMemoryArtifactLedgerRepository()
        );

        AgentContext context = AgentContext.builder()
                .requestId("req-failed-to-completed")
                .sessionId("session-failed-to-completed")
                .traceId("trace-failed-to-completed")
                .build();
        context.setAttribute("runId", "run-failed-to-completed");

        recordingService.recordRunStarted(context, "agent-finish", "react_workflow");
        recordingService.recordRunFinished(context, "FAILED", "first failed", "test_failure", "first failure");
        recordingService.recordRunFinished(context, "COMPLETED", "then completed", null, null);

        SessionLedgerEntity session = sessionRepository.findBySessionId("session-failed-to-completed").orElse(null);
        Assert.assertNotNull(session);
        Assert.assertEquals(Integer.valueOf(1), session.getRunCount());
        Assert.assertEquals(Integer.valueOf(1), session.getFinishedRunCount());
        Assert.assertEquals(Integer.valueOf(0), session.getFailedRunCount());
        Assert.assertEquals("COMPLETED", session.getStatus());
    }

    @Test
    public void shouldKeepRunLifecycleOwnerOutOfApplicationExecuteStrategies() {
        Assert.assertFalse(hasLedgerFactServiceField(ReactAgentExecuteStrategy.class));
        Assert.assertFalse(hasLedgerFactServiceField(WorkflowAgentExecuteStrategy.class));
    }

    private boolean hasLedgerFactServiceField(Class<?> strategyClass) {
        return Arrays.stream(strategyClass.getDeclaredFields())
                .anyMatch(field -> LedgerFactService.class.equals(field.getType()));
    }

    @Test
    public void shouldKeepFullLlmFactsWhenPreviewSummariesAreTruncated() {
        LedgerFixture fixture = newFixture();
        String requestTail = "REQUEST_TAIL_MARKER";
        String responseTail = "RESPONSE_TAIL_MARKER";
        String requestText = "r".repeat(360) + requestTail;
        String responseText = "s".repeat(360) + responseTail;
        LlmRequest request = LlmRequest.builder()
                .model("full-fact-model")
                .messages(List.of(LlmMessage.builder().role(LlmMessageRole.USER).content(requestText).build()))
                .attributes(Map.of(
                        "requestId", "req-full-llm",
                        "sessionId", "session-full-llm",
                        "traceId", "trace-full-llm",
                        "runId", "run-full-llm",
                        "scene", "react",
                        "step", 1
                ))
                .build();
        LlmResponse response = LlmResponse.builder()
                .provider("test-provider")
                .model("full-fact-model")
                .text(responseText)
                .finishReason("stop")
                .usage(LlmUsage.builder().promptTokens(101).completionTokens(102).totalTokens(203).build())
                .build();

        fixture.recordingService.recordLlmCall(request, response);

        LlmInvocationLedgerEntity invocation = fixture.llmRepository.findByRunId("run-full-llm").get(0);
        Assert.assertTrue(invocation.getRequestSummary().length() <= 320);
        Assert.assertTrue(invocation.getResponseSummary().length() <= 303);
        Assert.assertTrue(invocation.getRequestPayloadJson().contains(requestTail));
        Assert.assertTrue(invocation.getResponsePayloadJson().contains(responseTail));
        Assert.assertTrue(invocation.hasCompleteFactPayload());

        LedgerFactEntity fact = fixture.factService.query(LedgerFactQueryEntity.builder()
                        .traceId("trace-full-llm")
                        .factType(FactType.LLM_CALL)
                        .build())
                .get(0);
        Assert.assertEquals(Boolean.TRUE, fact.getPayload().get("completeFactAvailable"));
        Assert.assertTrue(String.valueOf(fact.getPayload().get("request")).contains(requestTail));
        Assert.assertTrue(String.valueOf(fact.getPayload().get("response")).contains(responseTail));
    }

    @Test
    public void shouldWriteV2CanonicalToolPayloadWithoutDuplicateResultCopies() {
        LedgerFixture fixture = newFixture();
        AgentContext context = AgentContext.builder()
                .requestId("req-full-tool")
                .sessionId("session-full-tool")
                .traceId("trace-full-tool")
                .currentStep(3)
                .build();
        context.setAttribute("runId", "run-full-tool");
        String argumentTail = "ARGUMENT_TAIL_MARKER";
        String outputTail = "OUTPUT_TAIL_MARKER";
        String observationTail = "OBSERVATION_TAIL_MARKER";
        ToolInvokeRequest request = ToolInvokeRequest.builder()
                .toolName("full_fact_tool")
                .toolCallId("call-full-tool")
                .arguments(Map.of("document", "a".repeat(350) + argumentTail))
                .metadata(Map.of("source", "test"))
                .requestId("req-full-tool")
                .sessionId("session-full-tool")
                .step(3)
                .build();
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("full_fact_tool")
                .toolCallId("call-full-tool")
                .sourceType("python")
                .success(false)
                .status("failed")
                .errorCode("test_failure")
                .errorMessage("full failure detail")
                .outputText("o".repeat(350) + outputTail)
                .llmObservation("v".repeat(350) + observationTail)
                .structuredOutput(Map.of("nested", Map.of("value", "z".repeat(350) + "STRUCTURED_TAIL_MARKER")))
                .ledgerFacts(List.of(Map.of("factType", "tool_failed", "detail", "full")))
                .artifactRefs(List.of("artifact://full-tool-output"))
                .startedAt(Instant.now())
                .finishedAt(Instant.now())
                .durationMs(88L)
                .build();

        fixture.recordingService.recordToolCall(context, request, result);

        var invocation = fixture.toolRepository.findByRunId("run-full-tool").get(0);
        Assert.assertTrue(invocation.getRequestSummary().length() <= 303);
        Assert.assertTrue(invocation.getResponseSummary().length() <= 303);
        Assert.assertTrue(invocation.getRequestPayloadJson().contains("\"schemaVersion\":2"));
        Assert.assertTrue(invocation.getRequestPayloadJson().contains(argumentTail));
        Assert.assertTrue(invocation.getResponsePayloadJson().contains("\"schemaVersion\":2"));
        Assert.assertFalse(invocation.getResponsePayloadJson().contains(outputTail));
        Assert.assertTrue(invocation.getResponsePayloadJson().contains(observationTail));
        Assert.assertTrue(invocation.getResponsePayloadJson().contains("STRUCTURED_TAIL_MARKER"));
        Assert.assertTrue(invocation.getResponsePayloadJson().contains("test_failure"));
        Assert.assertTrue(invocation.getResponsePayloadJson().contains("\"result\":"));
        Assert.assertFalse(invocation.getResponsePayloadJson().contains("\"outputText\":"));
        Assert.assertFalse(invocation.getResponsePayloadJson().contains("\"outputData\":"));
        Assert.assertFalse(invocation.getResponsePayloadJson().contains("\"structuredOutput\":"));
        Assert.assertFalse(invocation.getResponsePayloadJson().contains("\"rawPayload\":"));
        Assert.assertFalse(invocation.getExt().containsKey("llmObservation"));
        Assert.assertFalse(invocation.getExt().containsKey("structuredOutput"));

        LedgerFactEntity fact = fixture.factService.query(LedgerFactQueryEntity.builder()
                        .traceId("trace-full-tool")
                        .factType(FactType.TOOL_CALL)
                        .build())
                .get(0);
        Assert.assertEquals(Boolean.TRUE, fact.getPayload().get("completeFactAvailable"));
        Assert.assertTrue(String.valueOf(fact.getPayload().get("response")).contains(observationTail));
    }

    @Test
    public void shouldNotMergeLongLlmRequestsThatOnlyDifferAfterPreviewLimit() {
        LedgerFixture fixture = newFixture();
        String prefix = "p".repeat(350);
        fixture.recordingService.recordLlmCall(fullFactRequest(prefix + "FIRST_TAIL"), simpleLlmResponse());
        fixture.recordingService.recordLlmCall(fullFactRequest(prefix + "SECOND_TAIL"), simpleLlmResponse());

        List<LlmInvocationLedgerEntity> invocations = fixture.llmRepository.findByRunId("run-idempotency-full-fact");
        Assert.assertEquals(2, invocations.size());
        Assert.assertNotEquals(invocations.get(0).getIdempotencyKey(), invocations.get(1).getIdempotencyKey());
    }

    @Test
    public void shouldMarkHistoricalSummaryOnlyInvocationAsMissingCompleteFacts() {
        LedgerFixture fixture = newFixture();
        AgentContext context = AgentContext.builder()
                .requestId("req-history")
                .sessionId("session-history")
                .traceId("trace-history")
                .build();
        context.setAttribute("runId", "run-history");
        fixture.recordingService.recordRunStarted(context, "history-agent", "test");
        fixture.llmRepository.save(LlmInvocationLedgerEntity.builder()
                .llmInvocationId("legacy-llm")
                .runId("run-history")
                .sessionId("session-history")
                .requestId("req-history")
                .traceId("trace-history")
                .requestSummary("legacy request summary")
                .responseSummary("legacy response summary")
                .status("RECORDED")
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        LedgerFactEntity fact = fixture.factService.query(LedgerFactQueryEntity.builder()
                        .traceId("trace-history")
                        .factType(FactType.LLM_CALL)
                        .build())
                .get(0);
        Assert.assertEquals(Boolean.FALSE, fact.getPayload().get("completeFactAvailable"));
        Assert.assertFalse(fact.getPayload().containsKey("request"));
        Assert.assertFalse(fact.getPayload().containsKey("response"));
        Assert.assertTrue(fact.getExt().isEmpty());
    }

    @Test
    public void shouldKeepOriginalQueryTextPerRunWhenSessionHasMultipleQuestions() {
        LedgerFixture fixture = newFixture();
        AgentContext first = AgentContext.builder()
                .requestId("req-query-1")
                .sessionId("session-query")
                .traceId("trace-query-1")
                .query("first user question")
                .build();
        first.setAttribute("runId", "run-query-1");
        fixture.recordingService.recordRunStarted(first, "react-agent", "react");

        // Completion must not replace the original input fact, even if an upstream context is altered later.
        first.setQuery("incorrect replacement");
        fixture.recordingService.recordRunFinished(first, "COMPLETED", "first answer", null, null);

        AgentContext second = AgentContext.builder()
                .requestId("req-query-2")
                .sessionId("session-query")
                .traceId("trace-query-2")
                .query("second user question")
                .build();
        second.setAttribute("runId", "run-query-2");
        fixture.recordingService.recordRunStarted(second, "react-agent", "react");
        fixture.recordingService.recordRunFinished(second, "FAILED", null, "test", "expected");

        RunLedgerEntity firstRun = fixture.runRepository.findByRunId("run-query-1").orElseThrow();
        RunLedgerEntity secondRun = fixture.runRepository.findByRunId("run-query-2").orElseThrow();
        Assert.assertEquals("first user question", firstRun.getQueryText());
        Assert.assertEquals("second user question", secondRun.getQueryText());
        Assert.assertFalse(firstRun.getExt().containsKey("queryText"));
        Assert.assertFalse(secondRun.getExt().containsKey("queryText"));

        LedgerFactEntity runFact = fixture.factService.query(LedgerFactQueryEntity.builder()
                        .requestId("req-query-1")
                        .factType(FactType.AGENT_EVENT)
                        .build())
                .get(0);
        Assert.assertEquals("first user question", runFact.getInputSummary());
        Assert.assertEquals("react", runFact.getPayload().get("triggerSource"));
    }

    @Test
    public void shouldReadOnlyUseLegacyRunExtensionForExistingInputFacts() {
        LedgerFixture fixture = newFixture();
        fixture.runRepository.save(RunLedgerEntity.builder()
                .runId("run-legacy-query")
                .sessionId("session-legacy-query")
                .requestId("req-legacy-query")
                .traceId("trace-legacy-query")
                .agentId("react-agent")
                .triggerSource("react")
                .status("COMPLETED")
                .ext(Map.of("queryText", "persisted legacy input"))
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        LedgerFactEntity runFact = fixture.factService.query(LedgerFactQueryEntity.builder()
                        .requestId("req-legacy-query")
                        .factType(FactType.AGENT_EVENT)
                        .build())
                .get(0);

        Assert.assertEquals("persisted legacy input", runFact.getInputSummary());
    }

    private LlmRequest fullFactRequest(String content) {
        return LlmRequest.builder()
                .model("idempotency-model")
                .messages(List.of(LlmMessage.builder().role(LlmMessageRole.USER).content(content).build()))
                .attributes(Map.of(
                        "requestId", "req-idempotency-full-fact",
                        "sessionId", "session-idempotency-full-fact",
                        "traceId", "trace-idempotency-full-fact",
                        "runId", "run-idempotency-full-fact",
                        "scene", "react",
                        "step", 2
                ))
                .build();
    }

    private LlmResponse simpleLlmResponse() {
        return LlmResponse.builder()
                .provider("test-provider")
                .model("idempotency-model")
                .text("ok")
                .finishReason("stop")
                .build();
    }

    private LedgerFixture newFixture() {
        return new LedgerFixture();
    }

    private static class LedgerFixture {
        private final InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        private final InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        private final InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        private final InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        private final InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        private final LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository, runRepository, llmRepository, toolRepository, artifactRepository
        );
        private final LedgerQueryService queryService = new LedgerQueryService(
                sessionRepository, runRepository, llmRepository, toolRepository, artifactRepository
        );
        private final LedgerFactService factService = new LedgerFactService(recordingService, queryService);
    }
}
