package com.flocier.domain.agent.runtime.llm.response;

import com.flocier.domain.agent.runtime.llm.config.LlmBehaviorConfig;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 Spring AI 的响应映射为领域响应对象。
 */
@Component
public class LlmResponseMapper {

    public LlmResponse map(LlmResolvedModel resolvedModel, ChatResponse response, LlmBehaviorConfig behaviorConfig) {
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();

        Map<String, Object> extMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            extMetadata.put("id", metadata.getId());
            extMetadata.put("model", metadata.getModel());
            extMetadata.put("rateLimit", metadata.getRateLimit());
        }

        return LlmResponse.builder()
                .model(responseModel(resolvedModel, metadata))
                .provider(resolvedModel.getConfig().getProvider())
                .text(extractText(response))
                .finishReason(extractFinishReason(response))
                .usage(Boolean.TRUE.equals(behaviorConfig.getExtractUsage()) ? mapUsage(usage) : null)
                .metadata(extMetadata)
                .build();
    }

    private String responseModel(LlmResolvedModel resolvedModel, ChatResponseMetadata metadata) {
        if (metadata != null && metadata.getModel() != null) {
            return metadata.getModel();
        }
        return resolvedModel.getConfig().getModel();
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private String extractFinishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    public LlmUsage mapUsage(Usage usage) {
        if (usage == null) {
            return null;
        }
        return LlmUsage.builder()
                .promptTokens(usage.getPromptTokens())
                .completionTokens(usage.getCompletionTokens())
                .totalTokens(usage.getTotalTokens())
                .build();
    }
}
