package com.flocier.domain.agent.runtime.llm.toolask;

import com.flocier.domain.agent.runtime.LlmRuntimeDependencies;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolAskExecutor {

    private final LlmRuntimeDependencies llmRuntimeDependencies;
    private final LedgerFactService ledgerFactService;

    public ToolAskResponse execute(ToolAskRequest request) {
        ToolAskRequest effectiveRequest = enrichLlmCallId(validateAndReturn(request));

        LlmResolvedModel resolvedModel = llmRuntimeDependencies.settingsRegistry().resolve(effectiveRequest.getModel());
        OpenAiChatModel chatModel = llmRuntimeDependencies.modelResolver().resolve(resolvedModel);
        log.info("execute toolAsk, modelAlias={}, model={}, toolCount={}, step={}/{}",
                resolvedModel.getAlias(),
                resolvedModel.getConfig().getModel(),
                effectiveRequest.getToolDefinitions() == null ? 0 : effectiveRequest.getToolDefinitions().size(),
                effectiveRequest.getCurrentStep(),
                effectiveRequest.getMaxStep());
        Prompt prompt = new Prompt(
                llmRuntimeDependencies.messageConverter().toSpringMessages(effectiveRequest.getMessages()),
                llmRuntimeDependencies.chatOptionsFactory().buildToolCallOptions(resolvedModel, effectiveRequest)
        );

        ChatResponse response = chatModel.call(prompt);
        ToolAskResponse mapped = mapResponse(resolvedModel, response);
        log.info("toolAsk completed, finishReason={}, completed={}, toolCalls={}, thought={}",
                mapped.getFinishReason(),
                mapped.isCompleted(),
                mapped.getToolCalls() == null ? 0 : mapped.getToolCalls().size(),
                shorten(mapped.getThought()));
        if (mapped.getToolCalls() != null && !mapped.getToolCalls().isEmpty()) {
            for (ToolCall toolCall : mapped.getToolCalls()) {
                if (toolCall == null || toolCall.getFunction() == null) {
                    continue;
                }
                log.info("toolAsk toolCall, id={}, type={}, name={}, arguments={}",
                        toolCall.getId(),
                        toolCall.getType(),
                        toolCall.getFunction().getName(),
                        toolCall.getFunction().getArguments());
            }
        }
        LlmInvocationLedgerEntity invocation = ledgerFactService.recordToolAsk(effectiveRequest, mapped);
        if (invocation != null) {
            mapped.getMetadata().put("ledgerLlmInvocationId", invocation.getLlmInvocationId());
        }
        return mapped;
    }

    private ToolAskRequest validateAndReturn(ToolAskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ToolAskRequest must not be null");
        }
        return request;
    }

    /**
     * toolAsk 属于真正的 LLM 决策调用，必须有独立 llmCallId 才能保证账本步级轨迹完整。
     */
    private ToolAskRequest enrichLlmCallId(ToolAskRequest request) {
        Map<String, Object> attributes = request.getAttributes() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getAttributes());
        attributes.putIfAbsent("llmCallId", UUID.randomUUID().toString());
        return ToolAskRequest.builder()
                .model(request.getModel())
                .messages(request.getMessages())
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .toolDescriptionText(request.getToolDescriptionText())
                .toolDefinitions(request.getToolDefinitions())
                .currentStep(request.getCurrentStep())
                .maxStep(request.getMaxStep())
                .attributes(attributes)
                .build();
    }

    private ToolAskResponse mapResponse(LlmResolvedModel resolvedModel, ChatResponse response) {
        Generation generation = response == null ? null : response.getResult();
        AssistantMessage output = generation == null ? null : generation.getOutput();
        String rawText = output == null ? null : output.getText();

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (response != null && response.getMetadata() != null) {
            metadata.put("id", response.getMetadata().getId());
            metadata.put("model", response.getMetadata().getModel());
            metadata.put("rateLimit", response.getMetadata().getRateLimit());
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        if (output != null && output.getToolCalls() != null) {
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                if (toolCall == null) {
                    continue;
                }
                toolCalls.add(ToolCall.builder()
                        .id(toolCall.id())
                        .type(toolCall.type())
                        .function(ToolCall.Function.builder()
                                .name(toolCall.name())
                                .arguments(normalizeArguments(toolCall.arguments()))
                                .build())
                        .build());
            }
        }

        String finishReason = generation == null || generation.getMetadata() == null
                ? null
                : generation.getMetadata().getFinishReason();

        return ToolAskResponse.builder()
                .completed(toolCalls.isEmpty())
                .finishReason(finishReason)
                .thought(rawText)
                .toolCalls(toolCalls)
                .rawResponse(rawText)
                .model(response != null && response.getMetadata() != null && response.getMetadata().getModel() != null
                        ? response.getMetadata().getModel()
                        : resolvedModel.getConfig().getModel())
                .provider(resolvedModel.getConfig().getProvider())
                .usage(llmRuntimeDependencies.responseMapper().mapUsage(response == null || response.getMetadata() == null
                        ? null
                        : response.getMetadata().getUsage()))
                .metadata(metadata)
                .build();
    }

    private String normalizeArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        return arguments.trim();
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }
}
