package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.DefaultToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.PreparedToolExecution;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
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
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultToolRunnerLifecycleTest {

    @Test
    public void shouldPersistAndPublishSingleSuccessfulLifecycle() {
        Fixture fixture = new Fixture(false, false);

        ToolExecutionResult result = fixture.runner.run(new ToolCollection(List.of(new SuccessfulTool())),
                fixture.request("successful_tool", "call-success"), fixture.context());

        Assert.assertTrue(result.isSuccess());
        List<ToolInvocationLedgerEntity> invocations = fixture.toolRepository.findByRunId("run-lifecycle");
        Assert.assertEquals(1, invocations.size());
        Assert.assertEquals("success", invocations.get(0).getStatus());
        Assert.assertEquals("two_phase", invocations.get(0).getExt().get("lifecycle"));
        Assert.assertNotNull(invocations.get(0).getStartedAt());
        Assert.assertNotNull(invocations.get(0).getFinishedAt());
        Assert.assertEquals(List.of(RuntimeEventType.TOOL_STARTED, RuntimeEventType.TOOL_RESULT),
                fixture.events.stream().map(RuntimeEventDraft::getEventType).toList());
        Assert.assertEquals(invocations.get(0).getToolInvocationId(), fixture.events.get(0).factKey().split(":", 2)[1]);
    }

    @Test
    public void shouldPrepareBeforeExecutionAndKeepOneLifecycle() {
        Fixture fixture = new Fixture(false, false);
        PreparedToolExecution prepared = fixture.runner.prepare(new ToolCollection(List.of(new SuccessfulTool())),
                fixture.request("successful_tool", "call-prepared"), fixture.context());

        Assert.assertTrue(prepared.isReady());
        Assert.assertNotNull(prepared.getInvocation());
        Assert.assertEquals("RUNNING", prepared.getInvocation().getStatus());
        Assert.assertEquals(List.of(RuntimeEventType.TOOL_STARTED),
                fixture.events.stream().map(RuntimeEventDraft::getEventType).toList());

        ToolExecutionResult result = fixture.runner.execute(prepared, fixture.context());

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(1, fixture.toolRepository.findByRunId("run-lifecycle").size());
        Assert.assertEquals(List.of(RuntimeEventType.TOOL_STARTED, RuntimeEventType.TOOL_RESULT),
                fixture.events.stream().map(RuntimeEventDraft::getEventType).toList());
    }

    @Test
    public void shouldPersistMissingToolAsSingleFailedLifecycle() {
        Fixture fixture = new Fixture(false, false);

        ToolExecutionResult result = fixture.runner.run(new ToolCollection(List.of()),
                fixture.request("missing_tool", "call-missing"), fixture.context());

        Assert.assertFalse(result.isSuccess());
        List<ToolInvocationLedgerEntity> invocations = fixture.toolRepository.findByRunId("run-lifecycle");
        Assert.assertEquals(1, invocations.size());
        Assert.assertEquals("failed", invocations.get(0).getStatus());
        Assert.assertEquals("tool_not_found", invocations.get(0).getErrorCode());
        Assert.assertEquals(List.of(RuntimeEventType.TOOL_STARTED, RuntimeEventType.TOOL_RESULT),
                fixture.events.stream().map(RuntimeEventDraft::getEventType).toList());
    }

    @Test
    public void shouldNotExecuteOrPublishWhenStartPersistenceFails() {
        Fixture fixture = new Fixture(true, false);
        AtomicBoolean executed = new AtomicBoolean(false);
        BaseTool tool = new SuccessfulTool() {
            @Override
            public Object execute(Object input, AgentContext context) {
                executed.set(true);
                return super.execute(input, context);
            }
        };

        ToolExecutionResult result = fixture.runner.run(new ToolCollection(List.of(tool)),
                fixture.request("successful_tool", "call-start-failed"), fixture.context());

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("tool_ledger_start_failed", result.getErrorCode());
        Assert.assertFalse(executed.get());
        Assert.assertTrue(fixture.events.isEmpty());
        Assert.assertTrue(fixture.toolRepository.findByRunId("run-lifecycle").isEmpty());
    }

    @Test
    public void shouldKeepRunningFactAndSkipTerminalEventWhenFinishUpdateFails() {
        Fixture fixture = new Fixture(false, true);

        ToolExecutionResult result = fixture.runner.run(new ToolCollection(List.of(new SuccessfulTool())),
                fixture.request("successful_tool", "call-finish-failed"), fixture.context());

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("finish_failed", result.getAttributes().get("ledgerLifecycle"));
        List<ToolInvocationLedgerEntity> invocations = fixture.toolRepository.findByRunId("run-lifecycle");
        Assert.assertEquals(1, invocations.size());
        Assert.assertEquals("RUNNING", invocations.get(0).getStatus());
        Assert.assertNull(invocations.get(0).getFinishedAt());
        Assert.assertEquals(List.of(RuntimeEventType.TOOL_STARTED),
                fixture.events.stream().map(RuntimeEventDraft::getEventType).toList());
    }

    private static class Fixture {
        private final InMemoryToolInvocationLedgerRepository toolRepository;
        private final List<RuntimeEventDraft> events = new ArrayList<>();
        private final DefaultToolRunner runner = new DefaultToolRunner();

        private Fixture(boolean failSave, boolean failUpdate) {
            toolRepository = Mockito.spy(new InMemoryToolInvocationLedgerRepository());
            if (failSave) {
                Mockito.doThrow(new IllegalStateException("save failed")).when(toolRepository)
                        .save(Mockito.any(ToolInvocationLedgerEntity.class));
            }
            if (failUpdate) {
                Mockito.doThrow(new IllegalStateException("update failed")).when(toolRepository)
                        .update(Mockito.any(ToolInvocationLedgerEntity.class));
            }
            InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
            InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
            InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
            InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
            LedgerRecordingService recordingService = new LedgerRecordingService(
                    sessionRepository, runRepository, llmRepository, toolRepository, artifactRepository);
            LedgerQueryService queryService = new LedgerQueryService(
                    sessionRepository, runRepository, llmRepository, toolRepository, artifactRepository);
            RuntimeEventPublisher publisher = events::add;
            LedgerFactService ledgerFactService = new LedgerFactService(
                    recordingService, queryService, new RuntimeEventMapper(), publisher);
            ReflectionTestUtils.setField(runner, "ledgerFactService", ledgerFactService);
        }

        private AgentContext context() {
            AgentContext context = AgentContext.builder()
                    .requestId("request-lifecycle")
                    .sessionId("session-lifecycle")
                    .traceId("trace-lifecycle")
                    .currentStep(2)
                    .build();
            context.setAttribute("runId", "run-lifecycle");
            context.setAttribute("agentId", "react-agent");
            return context;
        }

        private ToolInvokeRequest request(String toolName, String toolCallId) {
            return ToolInvokeRequest.builder()
                    .toolName(toolName)
                    .toolCallId(toolCallId)
                    .requestId("request-lifecycle")
                    .sessionId("session-lifecycle")
                    .step(2)
                    .arguments(Map.of("message", "hello"))
                    .build();
        }
    }

    private static class SuccessfulTool implements BaseTool {
        @Override
        public String name() {
            return "successful_tool";
        }

        @Override
        public String description() {
            return "successful tool";
        }

        @Override
        public Object params() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return ToolResultPayload.builder()
                    .success(true)
                    .status("success")
                    .toolResult("done")
                    .llmObservation("工具已完成。")
                    .structuredOutput(Map.of("done", true))
                    .build();
        }
    }
}
