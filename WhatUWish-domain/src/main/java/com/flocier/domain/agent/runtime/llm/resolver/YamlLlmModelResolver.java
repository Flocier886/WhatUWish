package com.flocier.domain.agent.runtime.llm.resolver;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 YAML 模型配置解析可复用的 OpenAI-compatible ChatModel。
 */
@Slf4j
@Component
public class YamlLlmModelResolver implements LlmModelResolver {

    private final Map<String, OpenAiChatModel> modelCache = new ConcurrentHashMap<>();

    @Override
    public OpenAiChatModel resolve(LlmResolvedModel resolvedModel) {
        if (resolvedModel == null || resolvedModel.getConfig() == null) {
            throw new IllegalArgumentException("Resolved model must not be null");
        }
        LlmModelConfig config = resolvedModel.getConfig();
        String cacheKey = buildCacheKey(resolvedModel.getAlias(), config);
        return modelCache.computeIfAbsent(cacheKey, key -> createModel(resolvedModel));
    }

    private OpenAiChatModel createModel(LlmResolvedModel resolvedModel) {
        LlmModelConfig config = resolvedModel.getConfig();
        if (StringUtils.isBlank(config.getBaseUrl())) {
            throw new IllegalStateException("LLM baseUrl is empty for model alias: " + resolvedModel.getAlias());
        }
        if (StringUtils.isBlank(config.getModel())) {
            throw new IllegalStateException("LLM model name is empty for model alias: " + resolvedModel.getAlias());
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(config.getBaseUrl().trim())
                .apiKey(StringUtils.defaultString(config.getApiKey()))
                .completionsPath(StringUtils.defaultIfBlank(config.getInterfacePath(), "/v1/chat/completions"))
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.getModel())
                        .build())
                .build();

        log.info("初始化新 LLM ChatModel, alias={}, model={}", resolvedModel.getAlias(), config.getModel());
        return chatModel;
    }

    private String buildCacheKey(String alias, LlmModelConfig config) {
        return String.join("::",
                StringUtils.defaultString(alias),
                StringUtils.defaultString(config.getProvider()),
                StringUtils.defaultString(config.getModel()),
                StringUtils.defaultString(config.getBaseUrl()),
                StringUtils.defaultString(config.getInterfacePath()),
                StringUtils.defaultString(config.getApiKey()),
                JSON.toJSONString(config.getExtParams()));
    }
}
