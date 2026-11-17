package com.flocier.config;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmBehaviorConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmProviderConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmSettings;
import com.flocier.domain.agent.runtime.llm.support.LlmSettingsRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新 LLM 运行时装配入口。
 */
@Configuration
public class LlmRuntimeAutoConfiguration {

    @Bean
    public LlmSettings llmSettings(ReactorConfig reactorConfig) {
        LlmSettings settings = reactorConfig.getLlmSettings();
        if (settings == null) {
            return LlmSettings.builder()
                    .behavior(LlmBehaviorConfig.builder().build())
                    .models(new LinkedHashMap<String, LlmModelConfig>())
                    .providers(new LinkedHashMap<String, LlmProviderConfig>())
                    .build();
        }
        return settings;
    }

    @Bean
    public LlmSettingsRegistry llmSettingsRegistry(LlmSettings llmSettings) {
        return new LlmSettingsRegistry(llmSettings);
    }
}
