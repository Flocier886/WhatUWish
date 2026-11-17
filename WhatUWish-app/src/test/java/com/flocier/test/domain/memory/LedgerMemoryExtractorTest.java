package com.flocier.test.domain.memory;

import com.flocier.domain.agent.model.entity.LedgerMemoryBuildCommandEntity;
import com.flocier.domain.agent.model.entity.SessionMemoryView;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.response.LlmUsage;
import com.flocier.domain.agent.runtime.memory.LedgerMemoryExtractor;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerQueryService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.domain.ledger.service.ExecutionLedgerReadService;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class LedgerMemoryExtractorTest {

    @Test
    public void shouldUseCompleteInvocationPayloadBeforeLegacySummaryFallback() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository, runRepository, llmRepository, toolRepository, artifactRepository
        );
        LedgerQueryService queryService = new LedgerQueryService(
                sessionRepository, runRepository, llmRepository, toolRepository, artifactRepository
        );
        LedgerMemoryExtractor extractor = new LedgerMemoryExtractor(queryService,
                new ExecutionLedgerReadService(runRepository, llmRepository, toolRepository, artifactRepository));
        AgentContext context = AgentContext.builder()
                .requestId("req-memory-full-payload")
                .sessionId("session-memory-full-payload")
                .traceId("trace-memory-full-payload")
                .build();
        context.setAttribute("runId", "run-memory-full-payload");
        recordingService.recordRunStarted(context, "memory-agent", "test");
        llmRepository.save(LlmInvocationLedgerEntity.builder()
                .llmInvocationId("llm-memory-full-payload")
                .runId("run-memory-full-payload")
                .sessionId("session-memory-full-payload")
                .requestId("req-memory-full-payload")
                .traceId("trace-memory-full-payload")
                .requestSummary("legacy request preview")
                .responseSummary(null)
                .requestPayloadJson("{\"schemaVersion\":1,\"messages\":[{\"role\":\"USER\",\"content\":\"full request evidence\"}]}")
                .responsePayloadJson("{\"schemaVersion\":1,\"text\":\"fact sourced from complete payload\"}")
                .status("RECORDED")
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        SessionMemoryView memoryView = extractor.extract(LedgerMemoryBuildCommandEntity.builder()
                .sessionId("session-memory-full-payload")
                .currentRunId("other-run")
                .build());

        Assert.assertTrue(memoryView.getSessionHistory().getRuns().stream()
                .flatMap(run -> run.getCycles().stream())
                .anyMatch(cycle -> String.valueOf(cycle.getResponseText()).contains("fact sourced from complete payload")));
    }

    @Test
    public void shouldExtractSessionFactsTasksAndArtifactsFromLedger() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();

        LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository, runRepository, llmRepository, toolRepository, artifactRepository
        );
        LedgerQueryService queryService = new LedgerQueryService(
                sessionRepository, runRepository, llmRepository, toolRepository, artifactRepository
        );
        LedgerMemoryExtractor extractor = new LedgerMemoryExtractor(queryService,
                new ExecutionLedgerReadService(runRepository, llmRepository, toolRepository, artifactRepository));

        AgentContext context = AgentContext.builder()
                .requestId("req-memory-ledger")
                .sessionId("session-memory-ledger")
                .traceId("trace-memory-ledger")
                .currentStep(1)
                .result("最终已经完成账本记忆接入")
                .query("请继续开发 memory 模块")
                .task("请继续开发 memory 模块")
                .build();
        context.setAttribute("runId", "run-memory-ledger");

        recordingService.recordRunStarted(context, "agent-memory", "workflow");

        LlmRequest llmRequest = LlmRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(LlmMessage.builder()
                        .role(LlmMessageRole.USER)
                        .content("请总结当前进展")
                        .build()))
                .attributes(Map.of(
                        "requestId", "req-memory-ledger",
                        "sessionId", "session-memory-ledger",
                        "traceId", "trace-memory-ledger",
                        "runId", "run-memory-ledger",
                        "scene", "summary",
                        "step", 1
                ))
                .build();
        LlmResponse llmResponse = LlmResponse.builder()
                .model("gpt-4o-mini")
                .provider("default-openai")
                .text("当前已经完成账本落库，下一步应继续实现记忆注入与预算控制。")
                .finishReason("stop")
                .usage(LlmUsage.builder().promptTokens(12).completionTokens(18).totalTokens(30).build())
                .build();
        recordingService.recordLlmCall(llmRequest, llmResponse);

        ToolInvokeRequest toolInvokeRequest = ToolInvokeRequest.builder()
                .toolName("echo_tool")
                .toolCallId("call-memory-1")
                .arguments(Map.of("message", "任务推进到记忆预算控制"))
                .requestId("req-memory-ledger")
                .sessionId("session-memory-ledger")
                .step(1)
                .build();
        ToolExecutionResult toolExecutionResult = ToolExecutionResult.builder()
                .toolName("echo_tool")
                .toolCallId("call-memory-1")
                .sourceType("java")
                .success(true)
                .status("SUCCESS")
                .outputText("任务推进到记忆预算控制")
                .finishedAt(Instant.now())
                .durationMs(10L)
                .build();
        recordingService.recordToolCall(context, toolInvokeRequest, toolExecutionResult);
        recordingService.recordArtifact(ArtifactLedgerEntity.builder()
                .artifactId("artifact-memory-ledger-report")
                .runId("run-memory-ledger")
                .sessionId("session-memory-ledger")
                .requestId("req-memory-ledger")
                .traceId("trace-memory-ledger")
                .sourceType("tool_output")
                .sourceId("call-memory-1")
                .artifactType("file")
                .artifactName("memory-report.md")
                .storageType("file_ref")
                .storageRef("artifacts/run-memory-ledger/memory-report.md")
                .contentSummary("记忆模块开发进展报告")
                .mimeType("text/markdown")
                .idempotencyKey("artifact:file:run-memory-ledger:memory-report.md")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        recordingService.recordRunFinished(context, "COMPLETED", "当前已经完成账本落库，下一步实现记忆注入预算控制", null, null);

        SessionMemoryView memoryView = extractor.extract(LedgerMemoryBuildCommandEntity.builder()
                .sessionId("session-memory-ledger")
                .currentRunId("run-other")
                .currentRequestId("req-current")
                .build());

        Assert.assertNotNull(memoryView);
        Assert.assertEquals("session-memory-ledger", memoryView.getSessionId());
        Assert.assertNotNull(memoryView.getSessionHistory());
        Assert.assertEquals(1, memoryView.getSessionHistory().getRuns().size());
        Assert.assertFalse(memoryView.getSessionHistory().getRuns().get(0).getCycles().isEmpty());
        Assert.assertTrue(memoryView.getSessionHistory().getRuns().get(0).getOrphanTools().stream()
                .anyMatch(item -> "echo_tool".equals(item.getToolName())));
        Assert.assertTrue(memoryView.getSessionHistory().getRuns().get(0).getUnassociatedArtifacts().stream()
                .anyMatch(item -> "artifact-memory-ledger-report".equals(item.getArtifactId())));

        SessionMemoryView excludedCurrentRun = extractor.extract(LedgerMemoryBuildCommandEntity.builder()
                .sessionId("session-memory-ledger")
                .currentRunId("run-memory-ledger")
                .currentRequestId("req-current")
                .build());
        Assert.assertTrue(excludedCurrentRun.getSessionHistory().getRuns().isEmpty());
    }
}
