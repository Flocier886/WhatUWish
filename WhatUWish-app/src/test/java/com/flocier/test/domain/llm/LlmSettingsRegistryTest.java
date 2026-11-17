package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.llm.config.LlmBehaviorConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmProviderConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmSettings;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import com.flocier.domain.agent.runtime.llm.support.LlmSettingsRegistry;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class LlmSettingsRegistryTest {

    @Test
    public void shouldResolveDefaultModelAndMergeProviderFields() {
        Map<String, LlmProviderConfig> providers = new LinkedHashMap<>();
        providers.put("default-openai", LlmProviderConfig.builder()
                .baseUrl("https://provider.example.com")
                .apiKey("provider-key")
                .interfacePath("/v1/chat/completions")
                .extParams(Map.of("top_p", 0.9))
                .build());

        Map<String, LlmModelConfig> models = new LinkedHashMap<>();
        models.put("default-chat", LlmModelConfig.builder()
                .provider("default-openai")
                .model("gpt-4o-mini")
                .temperature(0.6)
                .maxTokens(1024)
                .extParams(Map.of("seed", 7))
                .build());

        LlmSettingsRegistry registry = new LlmSettingsRegistry(LlmSettings.builder()
                .defaultModel("default-chat")
                .models(models)
                .providers(providers)
                .behavior(new LlmBehaviorConfig())
                .build());

        LlmResolvedModel resolvedModel = registry.resolve(null);

        assertEquals("default-chat", resolvedModel.getAlias());
        assertEquals("gpt-4o-mini", resolvedModel.getConfig().getModel());
        assertEquals("https://provider.example.com", resolvedModel.getConfig().getBaseUrl());
        assertEquals("provider-key", resolvedModel.getConfig().getApiKey());
        assertEquals("/v1/chat/completions", resolvedModel.getConfig().getInterfacePath());
        assertEquals(Double.valueOf(0.9), resolvedModel.getConfig().getExtParams().get("top_p"));
        assertEquals(Integer.valueOf(7), resolvedModel.getConfig().getExtParams().get("seed"));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenModelAliasDoesNotExist() {
        LlmSettingsRegistry registry = new LlmSettingsRegistry(LlmSettings.builder()
                .defaultModel("missing-model")
                .models(new LinkedHashMap<>())
                .providers(new LinkedHashMap<>())
                .behavior(new LlmBehaviorConfig())
                .build());

        registry.resolve(null);
    }
}
