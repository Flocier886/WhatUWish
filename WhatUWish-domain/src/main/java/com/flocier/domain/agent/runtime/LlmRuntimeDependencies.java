package com.flocier.domain.agent.runtime;

import com.flocier.domain.agent.runtime.llm.convert.DomainMessageConverter;
import com.flocier.domain.agent.runtime.llm.options.OpenAiChatOptionsFactory;
import com.flocier.domain.agent.runtime.llm.resolver.LlmModelResolver;
import com.flocier.domain.agent.runtime.llm.response.LlmResponseMapper;
import com.flocier.domain.agent.runtime.llm.support.LlmSettingsRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 新 LLM 运行时依赖聚合器。
 */
@Component
@RequiredArgsConstructor
public class LlmRuntimeDependencies {

    private final LlmSettingsRegistry settingsRegistry;
    private final LlmModelResolver modelResolver;
    private final OpenAiChatOptionsFactory chatOptionsFactory;
    private final DomainMessageConverter messageConverter;
    private final LlmResponseMapper responseMapper;

    public LlmSettingsRegistry settingsRegistry() {
        return settingsRegistry;
    }

    public LlmModelResolver modelResolver() {
        return modelResolver;
    }

    public OpenAiChatOptionsFactory chatOptionsFactory() {
        return chatOptionsFactory;
    }

    public DomainMessageConverter messageConverter() {
        return messageConverter;
    }

    public LlmResponseMapper responseMapper() {
        return responseMapper;
    }
}
