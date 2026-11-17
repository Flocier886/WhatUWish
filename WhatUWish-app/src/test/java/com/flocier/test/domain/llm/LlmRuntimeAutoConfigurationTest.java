package com.flocier.test.domain.llm;

import com.flocier.config.LlmRuntimeAutoConfiguration;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmSettings;
import com.flocier.domain.agent.runtime.llm.support.LlmSettingsRegistry;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LlmRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ReactorConfig.class, LlmRuntimeAutoConfiguration.class)
            .withPropertyValues(
                    "reactor.llm.settings={\"defaults\":{\"model\":\"default-chat\"},\"behavior\":{\"extractUsage\":true,\"logRawResponse\":false,\"enableFallback\":false},\"providers\":{\"default-openai\":{\"baseUrl\":\"https://api.example.com\",\"apiKey\":\"test-key\",\"interfacePath\":\"/v1/chat/completions\"}},\"models\":{\"default-chat\":{\"provider\":\"default-openai\",\"model\":\"gpt-4o-mini\",\"temperature\":0.7,\"maxTokens\":2048,\"extParams\":{\"top_p\":0.8}}}}"
            );

    @Test
    public void shouldBindYamlStylePropertiesIntoLlmSettings() {
        contextRunner.run(context -> {
            ReactorConfig properties = context.getBean(ReactorConfig.class);
            LlmSettings settings = context.getBean(LlmSettings.class);
            LlmSettingsRegistry registry = context.getBean(LlmSettingsRegistry.class);

            assertNotNull(properties);
            assertNotNull(settings);
            assertNotNull(registry);

            assertEquals("default-chat", properties.getLlmSettings().getDefaultModel());
            assertEquals("default-chat", settings.getDefaultModel());
            assertEquals("gpt-4o-mini", settings.getModels().get("default-chat").getModel());
            assertEquals("https://api.example.com", settings.getProviders().get("default-openai").getBaseUrl());
            assertEquals(Double.valueOf(0.8), settings.getModels().get("default-chat").getExtParams().get("top_p"));
        });
    }

}
