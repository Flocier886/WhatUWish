package com.flocier.test.domain.memory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import com.flocier.domain.agent.service.execute.react.step.factory.DefaultReactWorkflowStrategyFactory;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.infrastructure.persistent.memory.InMemorySessionWorkingMemoryRepository;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

public class ReactWorkingMemoryStrategyTest {
    @Test
    public void reviewDispatchFailureCannotChangeDeliveredMainSuccess() throws Exception {
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        ReactAgentWorkflowExecuteStrategy strategy = strategy(repository, Mockito.mock(MemoryFacade.class), false);
        com.flocier.domain.agent.runtime.memory.review.BackgroundSkillReviewService reviews = Mockito.mock(
                com.flocier.domain.agent.runtime.memory.review.BackgroundSkillReviewService.class);
        Mockito.doThrow(new IllegalStateException("调度异常")).when(reviews).maybeScheduleAfterSuccess(Mockito.any());
        ReflectionTestUtils.setField(strategy, "skillReviewService", reviews);
        LedgerFactService ledger = (LedgerFactService) ReflectionTestUtils.getField(strategy, "ledgerFactService");
        AgentContext context = AgentContext.builder().build();
        com.flocier.domain.agent.runtime.printer.Printer printer = Mockito.mock(com.flocier.domain.agent.runtime.printer.Printer.class);
        context.setPrinter(printer);
        strategy.execute(command(), context);
        org.mockito.InOrder order = Mockito.inOrder(ledger, printer, reviews);
        order.verify(ledger).recordRunFinished(Mockito.eq(context), Mockito.eq("COMPLETED"), Mockito.anyString(), Mockito.isNull(), Mockito.isNull());
        order.verify(printer).send("result", "summary result");
        order.verify(printer).send("complete", "llm_completed");
        order.verify(reviews).maybeScheduleAfterSuccess(context);
        Mockito.verify(ledger, Mockito.never()).recordRunFinished(Mockito.any(), Mockito.eq("FAILED"), Mockito.any(), Mockito.any(), Mockito.any());
        Assert.assertFalse(repository.loadReadyMessages("session", "later").isEmpty());
    }

    @Test
    public void readyProjectionWinsOverLedgerAndOnlyNewReactMessagesAreSaved() throws Exception {
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        repository.persistTurn("session", "old", "old-run", List.of(message(LlmMessageRole.USER, "old task"),
                message(LlmMessageRole.ASSISTANT, "old answer")));
        MemoryFacade memory = Mockito.mock(MemoryFacade.class);
        ReactAgentWorkflowExecuteStrategy strategy = strategy(repository, memory, false);

        strategy.execute(command(), AgentContext.builder().build());
        List<LlmMessage> ready = repository.loadReadyMessages("session", "later");
        Assert.assertEquals(List.of("old task", "old answer", "current task", "react final"),
                ready.stream().map(LlmMessage::getContent).toList());
        Mockito.verify(memory, Mockito.never()).buildMemoryPrompt(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void ledgerBootstrapIsSavedOnceAndMaxStepKeepsCommittedBuildingMessages() throws Exception {
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        MemoryFacade memory = Mockito.mock(MemoryFacade.class);
        Mockito.when(memory.buildMemoryPrompt(Mockito.eq("session"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn("ledger history");
        strategy(repository, memory, false).execute(command(), AgentContext.builder().build());
        List<LlmMessage> ready = repository.loadReadyMessages("session", "later");
        Assert.assertEquals(3, ready.size());
        Assert.assertTrue(ready.get(0).getContent().contains("ledger history"));

        strategy(repository, memory, true).execute(command(), AgentContext.builder().build());
        Assert.assertEquals(5, repository.loadReadyMessages("session", "later").size());
        Mockito.verify(memory, Mockito.times(1))
                .buildMemoryPrompt(Mockito.eq("session"), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void compressedProjectionIsFollowedOnlyByNewSuffix() throws Exception {
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        MemoryFacade memory = Mockito.mock(MemoryFacade.class);
        ReactAgentWorkflowExecuteStrategy strategy = strategy(repository, memory, false);
        DefaultReactWorkflowStrategyFactory factory = (DefaultReactWorkflowStrategyFactory)
                ReflectionTestUtils.getField(strategy, "defaultReactWorkflowStrategyFactory");
        StrategyHandler<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> handler =
                factory.workflowStrategyHandler();
        Mockito.doAnswer(invocation -> {
            AgentContext context = ((DefaultReactWorkflowStrategyFactory.DynamicContext) invocation.getArgument(1))
                    .getAgentContext();
            List<LlmMessage> compressed = List.of(message(LlmMessageRole.USER, "summary"),
                    message(LlmMessageRole.USER, "current task"));
            repository.replaceReadyProjection("session", "compact-id", compressed);
            repository.appendMessage("session", "segment-after-compact", (String) context.getAttribute("runId"),
                    0, message(LlmMessageRole.ASSISTANT, "react final"));
            context.setAttribute("workingMemory.segmentId", "segment-after-compact");
            List<LlmMessage> snapshot = new ArrayList<>(compressed);
            snapshot.add(message(LlmMessageRole.ASSISTANT, "react final"));
            context.setReactMemorySnapshot(snapshot);
            context.setFinishReason("llm_completed");
            return "summary result";
        }).when(handler).apply(Mockito.any(), Mockito.any());
        strategy.execute(command(), AgentContext.builder().build());
        Assert.assertEquals(List.of("summary", "current task", "react final"),
                repository.loadReadyMessages("session", "later").stream().map(LlmMessage::getContent).toList());
    }

    @Test
    public void failedWorkflowDoesNotPersistTurn() throws Exception {
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        ReactAgentWorkflowExecuteStrategy strategy = strategy(repository, Mockito.mock(MemoryFacade.class), false);
        DefaultReactWorkflowStrategyFactory factory = (DefaultReactWorkflowStrategyFactory)
                ReflectionTestUtils.getField(strategy, "defaultReactWorkflowStrategyFactory");
        StrategyHandler<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> handler =
                factory.workflowStrategyHandler();
        Mockito.doThrow(new IllegalStateException("workflow failed"))
                .when(handler).apply(Mockito.any(), Mockito.any());
        try {
            strategy.execute(command(), AgentContext.builder().build());
            Assert.fail("Expected workflow failure");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(repository.loadReadyMessages("session", "later").isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private ReactAgentWorkflowExecuteStrategy strategy(InMemorySessionWorkingMemoryRepository repository,
            MemoryFacade memory, boolean maxStep) throws Exception {
        DefaultReactWorkflowStrategyFactory factory = Mockito.mock(DefaultReactWorkflowStrategyFactory.class);
        StrategyHandler<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> handler =
                Mockito.mock(StrategyHandler.class);
        Mockito.when(factory.workflowStrategyHandler()).thenReturn(handler);
        Mockito.when(handler.apply(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            DefaultReactWorkflowStrategyFactory.DynamicContext dynamic = invocation.getArgument(1);
            AgentContext context = dynamic.getAgentContext();
            List<LlmMessage> snapshot = new ArrayList<>(context.getWorkingMemoryMessages());
            String segmentId = context.getRequestId();
            String runId = context.getAttribute("runId");
            if (Boolean.TRUE.equals(context.getAttribute("workingMemory.ledgerBootstrap"))) {
                repository.appendMessage("session", segmentId, runId, 0, snapshot.get(0));
            }
            int seq = Boolean.TRUE.equals(context.getAttribute("workingMemory.ledgerBootstrap")) ? 1 : 0;
            repository.appendMessage("session", segmentId, runId, seq++, message(LlmMessageRole.USER, context.getTask()));
            repository.appendMessage("session", segmentId, runId, seq, message(LlmMessageRole.ASSISTANT, "react final"));
            context.setAttribute("workingMemory.segmentId", segmentId);
            snapshot.add(message(LlmMessageRole.USER, context.getTask()));
            snapshot.add(message(LlmMessageRole.ASSISTANT, "react final"));
            context.setWorkingMemoryPreloadSize(Boolean.TRUE.equals(context.getAttribute("workingMemory.ledgerBootstrap"))
                    ? 0 : context.getWorkingMemoryMessages().size());
            context.setReactMemorySnapshot(snapshot);
            context.setFinishReason(maxStep ? "max_step_reached" : "llm_completed");
            return "summary result";
        });
        ReactAgentWorkflowExecuteStrategy strategy = new ReactAgentWorkflowExecuteStrategy();
        ReflectionTestUtils.setField(strategy, "defaultReactWorkflowStrategyFactory", factory);
        ReflectionTestUtils.setField(strategy, "ledgerFactService", Mockito.mock(LedgerFactService.class));
        ReflectionTestUtils.setField(strategy, "memoryFacade", memory);
        ReflectionTestUtils.setField(strategy, "workingMemoryRepository", repository);
        ReflectionTestUtils.setField(strategy, "workingMemoryEnabled", true);
        return strategy;
    }

    private ExecuteCommandEntity command() {
        return ExecuteCommandEntity.builder().aiAgentId("test-agent")
                .sessionId("session").message("current task").build();
    }

    private LlmMessage message(LlmMessageRole role, String content) {
        return LlmMessage.builder().role(role).content(content).build();
    }
}
