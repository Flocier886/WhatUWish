package com.flocier.test.domain.ledger;

import com.flocier.application.agent.execute.react.ReactAgentExecuteStrategy;
import com.flocier.application.agent.execute.workflow.WorkflowAgentExecuteStrategy;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;
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

import java.util.Arrays;

/**
 * Run 生命周期所有权回归测试。
 * 约定：application 入口只做上下文适配，domain strategy / workflow 才是 run start / finish owner。
 */
public class RunLifecycleOwnershipTest {

    @Test
    public void applicationExecuteStrategiesShouldNotOwnRunLifecycle() {
        Assert.assertFalse(hasLedgerFactServiceField(ReactAgentExecuteStrategy.class));
        Assert.assertFalse(hasLedgerFactServiceField(WorkflowAgentExecuteStrategy.class));
    }

    @Test
    public void repeatedCompletedFinishShouldNotDriftSessionCounts() {
        LedgerFixture fixture = newFixture();
        AgentContext context = context("session-completed-repeat", "run-completed-repeat");

        fixture.recordingService.recordRunStarted(context, "agent-test", "react_workflow");
        fixture.recordingService.recordRunFinished(context, "COMPLETED", "done once", null, null);
        fixture.recordingService.recordRunFinished(context, "COMPLETED", "done twice", null, null);

        assertSessionCounts(fixture, "session-completed-repeat", 1, 1, 0, "COMPLETED");
        assertRunStatus(fixture, "run-completed-repeat", "COMPLETED", "done twice");
    }

    @Test
    public void repeatedFailedFinishShouldNotDriftSessionCounts() {
        LedgerFixture fixture = newFixture();
        AgentContext context = context("session-failed-repeat", "run-failed-repeat");

        fixture.recordingService.recordRunStarted(context, "agent-test", "react_workflow");
        fixture.recordingService.recordRunFinished(context, "FAILED", "failed once", "test_failure", "first failure");
        fixture.recordingService.recordRunFinished(context, "FAILED", "failed twice", "test_failure", "second failure");

        assertSessionCounts(fixture, "session-failed-repeat", 1, 0, 1, "FAILED");
        assertRunStatus(fixture, "run-failed-repeat", "FAILED", "failed twice");
    }

    @Test
    public void completedToFailedShouldRecalculateSessionCountsFromRunFacts() {
        LedgerFixture fixture = newFixture();
        AgentContext context = context("session-completed-to-failed", "run-completed-to-failed");

        fixture.recordingService.recordRunStarted(context, "agent-test", "react_workflow");
        fixture.recordingService.recordRunFinished(context, "COMPLETED", "first completed", null, null);
        fixture.recordingService.recordRunFinished(context, "FAILED", "then failed", "test_failure", "changed terminal state");

        assertSessionCounts(fixture, "session-completed-to-failed", 1, 0, 1, "FAILED");
        assertRunStatus(fixture, "run-completed-to-failed", "FAILED", "then failed");
    }

    @Test
    public void failedToCompletedShouldRecalculateSessionCountsFromRunFacts() {
        LedgerFixture fixture = newFixture();
        AgentContext context = context("session-failed-to-completed", "run-failed-to-completed");

        fixture.recordingService.recordRunStarted(context, "agent-test", "react_workflow");
        fixture.recordingService.recordRunFinished(context, "FAILED", "first failed", "test_failure", "first failure");
        fixture.recordingService.recordRunFinished(context, "COMPLETED", "then completed", null, null);

        assertSessionCounts(fixture, "session-failed-to-completed", 1, 1, 0, "COMPLETED");
        assertRunStatus(fixture, "run-failed-to-completed", "COMPLETED", "then completed");
    }

    @Test
    public void runExtensionShouldNotPersistRuntimeAttributes() {
        LedgerFixture fixture = newFixture();
        AgentContext context = context("session-run-ext", "run-run-ext");
        context.setAttribute("agentId", "agent-test");
        context.setAttribute("reactModel", "default-chat");
        context.setCurrentLlmInvocationId("llm-run-ext");
        context.setAttribute("react.lastPrompt", "x".repeat(20_000));
        context.setAttribute("react.trace", Arrays.asList("thought", "tool observation"));
        context.setAttribute("react.result", "transient result");
        context.setAttribute("summary.finalArtifactSelection", new Object());

        fixture.recordingService.recordRunStarted(context, "agent-test", "react_workflow");
        fixture.recordingService.recordRunFinished(context, "COMPLETED", "done", null, null);

        RunLedgerEntity run = fixture.runRepository.findByRunId("run-run-ext").orElse(null);
        Assert.assertNotNull(run);
        Assert.assertTrue(run.getExt().isEmpty());
    }

    @Test
    public void toolExtensionShouldNotPersistWholeAgentContext() {
        LedgerFixture fixture = newFixture();
        AgentContext context = context("session-tool-ext", "run-tool-ext");
        context.setAttribute("react.lastPrompt", "x".repeat(20_000));
        context.setAttribute("react.trace", Arrays.asList("thought", "tool observation"));

        fixture.recordingService.recordRunStarted(context, "agent-test", "react_workflow");
        fixture.recordingService.recordToolCall(context,
                ToolInvokeRequest.builder()
                        .toolName("test_tool")
                        .toolCallId("call-tool-ext")
                        .requestId(context.getRequestId())
                        .sessionId(context.getSessionId())
                        .step(1)
                        .build(),
                ToolExecutionResult.builder()
                        .toolName("test_tool")
                        .toolCallId("call-tool-ext")
                        .sourceType("java")
                        .success(true)
                        .status("success")
                        .outputText("tool output")
                        .llmObservation("tool observation")
                        .build());

        ToolInvocationLedgerEntity tool = fixture.toolRepository.findByRunId("run-tool-ext").get(0);
        Assert.assertFalse(tool.getExt().containsKey("contextAttributes"));
        Assert.assertFalse(tool.getExt().containsKey("llmObservation"));
        Assert.assertFalse(tool.getExt().containsKey("agentId"));
    }

    private boolean hasLedgerFactServiceField(Class<?> strategyClass) {
        return Arrays.stream(strategyClass.getDeclaredFields())
                .anyMatch(field -> LedgerFactService.class.equals(field.getType()));
    }

    private AgentContext context(String sessionId, String runId) {
        AgentContext context = AgentContext.builder()
                .requestId("req-" + runId)
                .sessionId(sessionId)
                .traceId("trace-" + runId)
                .build();
        context.setAttribute("runId", runId);
        return context;
    }

    private LedgerFixture newFixture() {
        InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        return new LedgerFixture(
                sessionRepository,
                runRepository,
                toolRepository,
                new LedgerRecordingService(
                        sessionRepository,
                        runRepository,
                        new InMemoryLlmInvocationLedgerRepository(),
                        toolRepository,
                        new InMemoryArtifactLedgerRepository()
                )
        );
    }

    private void assertSessionCounts(LedgerFixture fixture,
                                     String sessionId,
                                     int runCount,
                                     int finishedRunCount,
                                     int failedRunCount,
                                     String status) {
        SessionLedgerEntity session = fixture.sessionRepository.findBySessionId(sessionId).orElse(null);
        Assert.assertNotNull(session);
        Assert.assertEquals(Integer.valueOf(runCount), session.getRunCount());
        Assert.assertEquals(Integer.valueOf(finishedRunCount), session.getFinishedRunCount());
        Assert.assertEquals(Integer.valueOf(failedRunCount), session.getFailedRunCount());
        Assert.assertEquals(status, session.getStatus());
    }

    private void assertRunStatus(LedgerFixture fixture, String runId, String status, String summary) {
        RunLedgerEntity run = fixture.runRepository.findByRunId(runId).orElse(null);
        Assert.assertNotNull(run);
        Assert.assertEquals(status, run.getStatus());
        Assert.assertEquals(summary, run.getSummary());
    }

    private static class LedgerFixture {
        private final InMemorySessionLedgerRepository sessionRepository;
        private final InMemoryRunLedgerRepository runRepository;
        private final InMemoryToolInvocationLedgerRepository toolRepository;
        private final LedgerRecordingService recordingService;

        private LedgerFixture(InMemorySessionLedgerRepository sessionRepository,
                              InMemoryRunLedgerRepository runRepository,
                              InMemoryToolInvocationLedgerRepository toolRepository,
                              LedgerRecordingService recordingService) {
            this.sessionRepository = sessionRepository;
            this.runRepository = runRepository;
            this.toolRepository = toolRepository;
            this.recordingService = recordingService;
        }
    }
}
