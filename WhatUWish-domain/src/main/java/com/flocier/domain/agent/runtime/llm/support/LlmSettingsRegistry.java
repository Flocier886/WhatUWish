package com.flocier.domain.agent.runtime.llm.support;

import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmProviderConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmSettings;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 YAML 的 LLM 配置注册表。
 */
public class LlmSettingsRegistry {

    private final LlmSettings llmSettings;

    public LlmSettingsRegistry(LlmSettings llmSettings) {
        if (llmSettings == null) {
            throw new IllegalArgumentException("LlmSettings must not be null");
        }
        this.llmSettings = llmSettings;
    }

    public LlmSettings settings() {
        return llmSettings;
    }

    public LlmResolvedModel resolve(String requestedModel) {
        String alias = StringUtils.isNotBlank(requestedModel)
                ? requestedModel.trim()
                : StringUtils.trimToEmpty(llmSettings.getDefaultModel());
        if (StringUtils.isBlank(alias)) {
            throw new IllegalStateException("No default LLM model configured under reactor.llm.defaults.model");
        }

        LlmModelConfig modelConfig = llmSettings.getModels().get(alias);
        if (modelConfig == null) {
            throw new IllegalStateException("No LLM model registered for alias: " + alias);
        }

        return LlmResolvedModel.builder()
                .alias(alias)
                .config(mergeWithProvider(modelConfig))
                .build();
    }

    private LlmModelConfig mergeWithProvider(LlmModelConfig modelConfig) {
        LlmProviderConfig providerConfig = null;
        if (StringUtils.isNotBlank(modelConfig.getProvider()) && llmSettings.getProviders() != null) {
            providerConfig = llmSettings.getProviders().get(modelConfig.getProvider());
        }

        Map<String, Object> extParams = new LinkedHashMap<>();
        if (providerConfig != null && providerConfig.getExtParams() != null) {
            extParams.putAll(providerConfig.getExtParams());
        }
        if (modelConfig.getExtParams() != null) {
            extParams.putAll(modelConfig.getExtParams());
        }

        return LlmModelConfig.builder()
                .provider(modelConfig.getProvider())
                .model(firstNonBlank(modelConfig.getModel(), null))
                .baseUrl(firstNonBlank(modelConfig.getBaseUrl(), providerConfig == null ? null : providerConfig.getBaseUrl()))
                .apiKey(firstNonBlank(modelConfig.getApiKey(), providerConfig == null ? null : providerConfig.getApiKey()))
                .interfacePath(firstNonBlank(modelConfig.getInterfacePath(), providerConfig == null ? null : providerConfig.getInterfacePath()))
                .temperature(modelConfig.getTemperature())
                .maxTokens(modelConfig.getMaxTokens())
                .timeoutSeconds(modelConfig.getTimeoutSeconds())
                .extParams(extParams)
                .build();
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.isNotBlank(preferred) ? preferred.trim() : StringUtils.trimToNull(fallback);
    }
}
