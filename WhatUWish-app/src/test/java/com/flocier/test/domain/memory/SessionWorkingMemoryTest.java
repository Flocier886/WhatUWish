package com.flocier.test.domain.memory;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.memory.SessionContextCompactionService;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryCompactor;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryProjector;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionWorkingMemoryRepository;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

public class SessionWorkingMemoryTest {
    @Test
    public void projectsNativeToolMessagesAndKeepsInvalidHistoryOutOfReadyProjection() {
        ToolCall call = ToolCall.builder().id("tool-1").type("function")
                .function(ToolCall.Function.builder().name("search").arguments("{\"q\":\"x\"}").build()).build();
        List<LlmMessage> turn = List.of(
                message(LlmMessageRole.USER, "question"),
                LlmMessage.builder().role(LlmMessageRole.ASSISTANT).content("searching").toolCalls(List.of(call)).build(),
                LlmMessage.builder().role(LlmMessageRole.TOOL).toolCallId("tool-1").content("result").build(),
                message(LlmMessageRole.ASSISTANT, "answer"));
        WorkingMemoryProjector projector = new WorkingMemoryProjector();
        List<LlmMessage> hydrated = projector.hydrate(projector.project(turn, "session", "request-1", "run-1"));
        Assert.assertEquals(turn.size(), hydrated.size());
        for (int i = 0; i < turn.size(); i++) {
            Assert.assertEquals(turn.get(i).getRole(), hydrated.get(i).getRole());
            Assert.assertEquals(turn.get(i).getContent(), hydrated.get(i).getContent());
            Assert.assertEquals(turn.get(i).getToolCalls(), hydrated.get(i).getToolCalls());
            Assert.assertEquals(turn.get(i).getToolCallId(), hydrated.get(i).getToolCallId());
            Assert.assertEquals("request-1:" + i, hydrated.get(i).getMetadata().get("originMessageKey"));
        }

        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        Assert.assertTrue(repository.persistTurn("session", "request-1", "run-1", turn));
        Assert.assertFalse(repository.persistTurn("session", "request-1", "run-1", turn));
        Assert.assertEquals(4, repository.loadReadyMessages("session", "next-request").size());
        Assert.assertTrue(repository.replaceReadyProjection("session", "wm-compact-1",
                List.of(message(LlmMessageRole.USER, "summary"))));
        Assert.assertEquals(1, repository.loadReadyMessages("session", "next-request").size());
        Assert.assertTrue(repository.loadReadyMessages("session", "wm-compact-1").isEmpty());
    }

    @Test
    public void microcompactionPreservesToolCallId() {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        ToolCall call = ToolCall.builder().id("call-1").type("function")
                .function(ToolCall.Function.builder().name("search").arguments("{}").build()).build();
        List<LlmMessage> source = List.of(
                LlmMessage.builder().role(LlmMessageRole.ASSISTANT).toolCalls(List.of(call)).build(),
                LlmMessage.builder().role(LlmMessageRole.TOOL).toolCallId("call-1").content("large result").build(),
                message(LlmMessageRole.USER, "new task"));
        List<LlmMessage> result = compactor.microcompact(source, 0, 100);
        Assert.assertEquals("call-1", result.get(1).getToolCallId());
        Assert.assertEquals(WorkingMemoryCompactor.CLEARED_TOOL_RESULT, result.get(1).getContent());
        Assert.assertEquals("call-1", result.get(0).getToolCalls().get(0).getId());
    }

    @Test
    public void dropOldestKeepsLatestUserAndWholeToolPair() {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        ToolCall call = ToolCall.builder().id("old-call").type("function")
                .function(ToolCall.Function.builder().name("search").arguments("{}").build()).build();
        List<LlmMessage> source = List.of(
                message(LlmMessageRole.USER, "old task ".repeat(500)),
                LlmMessage.builder().role(LlmMessageRole.ASSISTANT).toolCalls(List.of(call)).build(),
                LlmMessage.builder().role(LlmMessageRole.TOOL).toolCallId("old-call")
                        .content("old observation ".repeat(500)).build(),
                message(LlmMessageRole.USER, "latest task"),
                message(LlmMessageRole.ASSISTANT, "recent answer"));
        List<LlmMessage> result = compactor.dropOldestToFit(source, 100);
        Assert.assertEquals("latest task", result.get(0).getContent());
        Assert.assertEquals("recent answer", result.get(1).getContent());
        Assert.assertEquals(2, result.size());
    }

    @Test
    public void summaryWindowKeepsRecentToolPairAndUpdatesPreviousSummary() {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        ToolCall call = ToolCall.builder().id("recent-call").type("function")
                .function(ToolCall.Function.builder().name("read").arguments("{}").build()).build();
        List<LlmMessage> history = new ArrayList<>();
        history.add(message(LlmMessageRole.USER, WorkingMemoryCompactor.SUMMARY_PREFIX + "\nprevious facts"));
        for (int i = 0; i < 12; i++) {
            history.add(message(LlmMessageRole.USER, "older " + i));
            history.add(message(LlmMessageRole.ASSISTANT, "answer " + i));
        }
        history.add(message(LlmMessageRole.USER, "latest task"));
        history.add(LlmMessage.builder().role(LlmMessageRole.ASSISTANT).toolCalls(List.of(call)).build());
        history.add(LlmMessage.builder().role(LlmMessageRole.TOOL).toolCallId("recent-call")
                .content("recent observation").build());
        WorkingMemoryCompactor.Window window = compactor.split(history, 20, 2);
        Assert.assertNotNull(window);
        List<LlmMessage> compacted = compactor.buildCompacted(window, "updated facts");
        Assert.assertEquals(1, compacted.stream().filter(compactor::isSummary).count());
        Assert.assertEquals("latest task", compacted.get(compacted.size() - 3).getContent());
        Assert.assertEquals("recent-call", compacted.get(compacted.size() - 2).getToolCalls().get(0).getId());
        Assert.assertEquals("recent-call", compacted.get(compacted.size() - 1).getToolCallId());
        Assert.assertEquals(4, compactor.serialize(history, 4).length());
    }

    @Test
    public void transientSummaryFailureUsesLocalFallback() {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.callText(Mockito.any())).thenThrow(new IllegalStateException("temporary network error"));
        SessionContextCompactionService service = service(compactor, repository, llm);
        List<LlmMessage> result = service.applyIfNeeded("session", "request", "system", "tools", largeHistory());
        Assert.assertTrue(compactor.isSummary(result.get(0)));
        Assert.assertEquals("latest task", result.get(result.size() - 1).getContent());
        Assert.assertEquals(result, repository.loadReadyMessages("session", "later"));
    }

    @Test
    public void permanentSummaryFailureLeavesMessagesUnchanged() {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.callText(Mockito.any())).thenThrow(new IllegalStateException("401 unauthorized"));
        SessionContextCompactionService service = service(compactor, repository, llm);
        List<LlmMessage> source = largeHistory();
        Assert.assertEquals(source, service.applyIfNeeded("session", "request", "system", "tools", source));
        Assert.assertTrue(repository.loadReadyMessages("session", "later").isEmpty());
    }

    @Test
    public void failedProjectionReplaceLeavesCurrentRunMessagesUnchanged() {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        SessionWorkingMemoryRepository repository = Mockito.mock(SessionWorkingMemoryRepository.class);
        Mockito.when(repository.replaceReadyProjection(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenThrow(new IllegalStateException("database unavailable"));
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.callText(Mockito.any())).thenReturn("## Goal\nContinue the latest task.");
        SessionContextCompactionService service = service(compactor, repository, llm);
        List<LlmMessage> source = largeHistory();
        Assert.assertEquals(source, service.applyIfNeeded("session", "request", "system", "tools", source));
        Mockito.verify(repository).recordCompaction(Mockito.argThat(event -> event.getStatus() == 2));
    }

    @Test
    public void midRunCompactionReplacesProjectionAndKeepsLatestTask() {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.callText(Mockito.any())).thenReturn("## Goal\nContinue the task with verified facts.");
        SessionContextCompactionService service = service(compactor, repository, llm);
        List<LlmMessage> source = largeHistory();
        repository.persistTurn("session", "old-request", "run", source);
        List<LlmMessage> compacted = service.applyIfNeeded("session", "new-request", "system", "tools", source);
        Assert.assertTrue(compacted.size() < source.size());
        Assert.assertEquals("latest task", compacted.get(compacted.size() - 1).getContent());
        Assert.assertEquals(compacted, repository.loadReadyMessages("session", "later-request"));
        Mockito.verify(llm).callText(Mockito.any());
    }

    private SessionContextCompactionService service(WorkingMemoryCompactor compactor,
            SessionWorkingMemoryRepository repository, LLM llm) {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "reactModelName", "test-model");
        SessionContextCompactionService service = new SessionContextCompactionService(compactor, repository, llm, config);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "compactionEnabled", true);
        ReflectionTestUtils.setField(service, "contextWindow", 1000);
        ReflectionTestUtils.setField(service, "thresholdPercent", 0.5);
        ReflectionTestUtils.setField(service, "keepMinTokens", 20);
        ReflectionTestUtils.setField(service, "keepMinTextMessages", 2);
        ReflectionTestUtils.setField(service, "keepRecentTools", 5);
        ReflectionTestUtils.setField(service, "toolResultMaxChars", 8000);
        ReflectionTestUtils.setField(service, "summaryInputMaxChars", 160000);
        ReflectionTestUtils.setField(service, "summarizerTimeoutSeconds", 5);

        return service;
    }

    private List<LlmMessage> largeHistory() {
        List<LlmMessage> source = new ArrayList<>();
        source.add(message(LlmMessageRole.USER, "old task"));
        for (int i = 0; i < 12; i++) {
            source.add(message(LlmMessageRole.ASSISTANT, ("fact " + i + " ").repeat(100)));
            source.add(message(LlmMessageRole.USER, ("followup " + i + " ").repeat(100)));
        }
        source.add(message(LlmMessageRole.USER, "latest task"));
        return source;
    }

    private LlmMessage message(LlmMessageRole role, String content) {
        return LlmMessage.builder().role(role).content(content).build();
    }
}
