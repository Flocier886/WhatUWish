package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.llm.convert.DomainMessageConverter;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DomainMessageConverterTest {

    @Test
    public void shouldConvertAssistantToolCallsAndToolResponses() {
        List<LlmMessage> messages = List.of(
                LlmMessage.builder().role(LlmMessageRole.SYSTEM).content("system prompt").build(),
                LlmMessage.builder().role(LlmMessageRole.USER).content("hello").build(),
                LlmMessage.builder()
                        .role(LlmMessageRole.ASSISTANT)
                        .content("我准备调用工具")
                        .toolCalls(List.of(
                                ToolCall.builder()
                                        .id("tool-1")
                                        .type("function")
                                        .function(ToolCall.Function.builder()
                                                .name("weather")
                                                .arguments("{\"city\":\"shanghai\"}")
                                                .build())
                                        .build()
                        ))
                        .build(),
                LlmMessage.builder()
                        .role(LlmMessageRole.TOOL)
                        .toolCallId("tool-1")
                        .content("{\"city\":\"shanghai\"}")
                        .build()
        );

        List<Message> converted = new DomainMessageConverter().toSpringMessages(messages);

        assertEquals(4, converted.size());
        assertEquals(MessageType.SYSTEM, converted.get(0).getMessageType());
        assertEquals(MessageType.USER, converted.get(1).getMessageType());
        assertEquals(MessageType.ASSISTANT, converted.get(2).getMessageType());
        assertEquals(MessageType.TOOL, converted.get(3).getMessageType());

        AssistantMessage assistantMessage = (AssistantMessage) converted.get(2);
        assertTrue(assistantMessage.hasToolCalls());
        assertEquals(1, assistantMessage.getToolCalls().size());
        assertEquals("tool-1", assistantMessage.getToolCalls().get(0).id());
        assertEquals("weather", assistantMessage.getToolCalls().get(0).name());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) converted.get(3);
        assertEquals(1, toolResponseMessage.getResponses().size());
        assertEquals("tool-1", toolResponseMessage.getResponses().get(0).id());
        assertEquals("weather", toolResponseMessage.getResponses().get(0).name());
        assertEquals("{\"city\":\"shanghai\"}", toolResponseMessage.getResponses().get(0).responseData());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectToolMessageWithoutAssistantAnchor() {
        new DomainMessageConverter().toSpringMessages(List.of(
                LlmMessage.builder()
                        .role(LlmMessageRole.TOOL)
                        .toolCallId("missing")
                        .content("bad")
                        .build()
        ));
    }
}
