package com.flocier.test.domain.memory;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.infrastructure.persistent.memory.InMemorySessionWorkingMemoryRepository;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/** 增量段、并发工具批次恢复和压缩续写的存储契约。 */
public class IncrementalWorkingMemoryTest {
    @Test
    public void incompleteBatchIsRetainedButOnlyCompletePrefixIsLoaded() {
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        repository.appendMessage("s", "r", "run", 0, message(LlmMessageRole.USER, "task"));
        ToolCall first = call("first");
        ToolCall second = call("second");
        repository.appendMessage("s", "r", "run", 1,
                LlmMessage.builder().role(LlmMessageRole.ASSISTANT).toolCalls(List.of(first, second)).build());
        repository.appendMessage("s", "r", "run", 2,
                LlmMessage.builder().role(LlmMessageRole.TOOL).toolCallId("first").content("first result").build());
        Assert.assertEquals(List.of("task"), repository.loadReadyMessages("s", "next").stream()
                .map(LlmMessage::getContent).toList());
        repository.appendMessage("s", "r", "run", 3,
                LlmMessage.builder().role(LlmMessageRole.TOOL).toolCallId("second").content("second result").build());
        Assert.assertEquals(List.of("first", "second"), repository.loadReadyMessages("s", "next").stream()
                .filter(item -> item.getRole() == LlmMessageRole.TOOL).map(LlmMessage::getToolCallId).toList());
    }

    @Test
    public void retriesAreIdempotentAndCompressionStartsANewSegment() {
        InMemorySessionWorkingMemoryRepository repository = new InMemorySessionWorkingMemoryRepository();
        LlmMessage user = message(LlmMessageRole.USER, "task");
        repository.appendMessage("s", "r", "run", 0, user);
        repository.appendMessage("s", "r", "run", 0, user);
        Assert.assertTrue(repository.hasRequest("r"));
        try {
            repository.appendMessage("s", "r", "run", 0, message(LlmMessageRole.USER, "changed"));
            Assert.fail("conflicting retry must fail");
        } catch (IllegalStateException expected) {
            Assert.assertEquals(1, repository.loadReadyMessages("s", "next").size());
        }
        Assert.assertTrue(repository.replaceReadyProjection("s", "compact", List.of(user)));
        repository.appendMessage("s", "segment-1", "run", 0, message(LlmMessageRole.ASSISTANT, "answer"));
        repository.completeSegment("segment-1");
        Assert.assertEquals(List.of("task", "answer"), repository.loadReadyMessages("s", "next").stream()
                .map(LlmMessage::getContent).toList());
        try {
            repository.appendMessage("s", "r", "run", 1, message(LlmMessageRole.USER, "late"));
            Assert.fail("invalidated segment must not be appendable");
        } catch (IllegalStateException expected) {
            Assert.assertEquals(2, repository.loadReadyMessages("s", "next").size());
        }
    }

    private LlmMessage message(LlmMessageRole role, String content) {
        return LlmMessage.builder().role(role).content(content).build();
    }

    private ToolCall call(String id) {
        return ToolCall.builder().id(id).function(ToolCall.Function.builder()
                .name("tool").arguments("{}").build()).build();
    }
}
