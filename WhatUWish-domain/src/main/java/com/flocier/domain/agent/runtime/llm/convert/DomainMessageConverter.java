package com.flocier.domain.agent.runtime.llm.convert;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DomainMessageConverter {

    public List<Message> toSpringMessages(List<LlmMessage> messages) {
        List<Message> converted = new ArrayList<>();
        Map<String, String> toolCallNameIndex = new LinkedHashMap<>();
        if (messages == null || messages.isEmpty()) {
            return converted;
        }
        for (LlmMessage message : messages) {
            if (message == null || message.getRole() == null) {
                continue;
            }
            Message springMessage = toSpringMessage(message, toolCallNameIndex);
            if (springMessage != null) {
                converted.add(springMessage);
            }
            indexToolCalls(message, toolCallNameIndex);
        }
        return converted;
    }

    private void indexToolCalls(LlmMessage message, Map<String, String> toolCallNameIndex) {
        if (message.getToolCalls() == null || message.getToolCalls().isEmpty()) {
            return;
        }
        for (ToolCall toolCall : message.getToolCalls()) {
            if (toolCall == null || toolCall.getFunction() == null) {
                continue;
            }
            if (toolCall.getId() == null || toolCall.getId().isBlank()) {
                continue;
            }
            if (toolCall.getFunction().getName() == null || toolCall.getFunction().getName().isBlank()) {
                continue;
            }
            toolCallNameIndex.put(toolCall.getId(), toolCall.getFunction().getName());
        }
    }

    private Message toSpringMessage(LlmMessage message, Map<String, String> toolCallNameIndex) {
        LlmMessageRole role = message.getRole();
        String content = message.getContent() == null ? "" : message.getContent();
        return switch (role) {
            case SYSTEM -> new SystemMessage(content);
            case USER -> new UserMessage(content);
            case ASSISTANT -> toAssistantMessage(message, content);
            case TOOL -> toToolResponseMessage(message, content, toolCallNameIndex);
        };
    }

    private Message toAssistantMessage(LlmMessage message, String content) {
        AssistantMessage.Builder builder = AssistantMessage.builder()
                .content(content)
                .properties(Map.of());
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            for (ToolCall toolCall : message.getToolCalls()) {
                if (toolCall == null || toolCall.getFunction() == null) {
                    continue;
                }
                toolCalls.add(new AssistantMessage.ToolCall(
                        toolCall.getId(),
                        toolCall.getType(),
                        toolCall.getFunction().getName(),
                        toolCall.getFunction().getArguments()
                ));
            }
            builder.toolCalls(toolCalls);
        }
        return builder.build();
    }

    private Message toToolResponseMessage(LlmMessage message,
                                          String content,
                                          Map<String, String> toolCallNameIndex) {
        String toolCallId = message.getToolCallId() == null ? "" : message.getToolCallId().trim();
        String toolName = toolCallNameIndex.get(toolCallId);
        if (toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
            throw new IllegalStateException("Cannot resolve tool name for toolCallId: " + toolCallId);
        }
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(toolCallId, toolName, content)))
                .build();
    }
}
