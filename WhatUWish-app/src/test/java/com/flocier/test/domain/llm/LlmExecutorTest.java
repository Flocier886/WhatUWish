package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.LlmRuntimeDependencies;
import com.flocier.domain.agent.runtime.llm.config.LlmBehaviorConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmSettings;
import com.flocier.domain.agent.runtime.llm.convert.DomainMessageConverter;
import com.flocier.domain.agent.runtime.llm.executor.LlmExecutor;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.options.OpenAiChatOptionsFactory;
import com.flocier.domain.agent.runtime.llm.resolver.LlmModelResolver;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.response.LlmResponseMapper;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import com.flocier.domain.agent.runtime.llm.support.LlmSettingsRegistry;
import com.flocier.domain.ledger.service.LedgerFactService;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class LlmExecutorTest {

    @Test
    public void shouldExecuteFullLlmChainAndReturnMappedResponse() {
        LlmSettings settings = LlmSettings.builder()
                .defaultModel("default-chat")
                .models(new LinkedHashMap<>(Map.of(
                        "default-chat", LlmModelConfig.builder()
                                .provider("default-openai")
                                .model("gpt-4o-mini")
                                .baseUrl("https://api.example.com")
                                .apiKey("test-key")
                                .interfacePath("/v1/chat/completions")
                                .temperature(0.7)
                                .maxTokens(2048)
                                .build()
                )))
                .behavior(LlmBehaviorConfig.builder().extractUsage(true).build())
                .build();

        LlmSettingsRegistry settingsRegistry = new LlmSettingsRegistry(settings);
        LlmResolvedModel resolvedModel = settingsRegistry.resolve(null);

        LlmModelResolver modelResolver = Mockito.mock(LlmModelResolver.class);
        OpenAiChatModel chatModel = Mockito.mock(OpenAiChatModel.class);
        LedgerFactService ledgerFactService = Mockito.mock(LedgerFactService.class);
        when(modelResolver.resolve(any(LlmResolvedModel.class))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(buildChatResponse());

        LlmRuntimeDependencies dependencies = new LlmRuntimeDependencies(
                settingsRegistry,
                modelResolver,
                new OpenAiChatOptionsFactory(),
                new DomainMessageConverter(),
                new LlmResponseMapper()
        );

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(LlmMessage.builder()
                        .role(LlmMessageRole.USER)
                        .content("test request")
                        .build()))
                .build();

        LlmResponse response = new LlmExecutor(dependencies, ledgerFactService).call(request);

        assertNotNull(response);
        assertEquals("gpt-4o-mini", response.getModel());
        assertEquals("default-openai", response.getProvider());
        assertEquals("this is a test response", response.getText());
        assertEquals("stop", response.getFinishReason());
        assertEquals(Integer.valueOf(10), response.getUsage().getPromptTokens());
        assertEquals(Integer.valueOf(20), response.getUsage().getCompletionTokens());
        assertEquals(Integer.valueOf(30), response.getUsage().getTotalTokens());
        assertEquals("chatcmpl-test", response.getMetadata().get("id"));
        assertEquals(resolvedModel.getConfig().getModel(), response.getMetadata().get("model"));
        Mockito.verify(ledgerFactService).recordLlmCall(request, response);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldRejectStreamingRequestInPhaseOne() {
        LlmSettings settings = LlmSettings.builder()
                .defaultModel("default-chat")
                .models(new LinkedHashMap<>(Map.of(
                        "default-chat", LlmModelConfig.builder()
                                .model("gpt-4o-mini")
                                .baseUrl("https://api.example.com")
                                .build()
                )))
                .behavior(new LlmBehaviorConfig())
                .build();

        LlmRuntimeDependencies dependencies = new LlmRuntimeDependencies(
                new LlmSettingsRegistry(settings),
                Mockito.mock(LlmModelResolver.class),
                new OpenAiChatOptionsFactory(),
                new DomainMessageConverter(),
                new LlmResponseMapper()
        );

        new LlmExecutor(dependencies, Mockito.mock(LedgerFactService.class))
                .call(LlmRequest.builder().stream(Boolean.TRUE).build());
    }

    private ChatResponse buildChatResponse() {
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 10;
            }

            @Override
            public Integer getCompletionTokens() {
                return 20;
            }

            @Override
            public Integer getTotalTokens() {
                return 30;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id("chatcmpl-test")
                .model("gpt-4o-mini")
                .usage(usage)
                .build();

        Generation generation = new Generation(
                new AssistantMessage("this is a test response"),
                ChatGenerationMetadata.builder()
                        .finishReason("stop")
                        .build()
        );

        return new ChatResponse(List.of(generation), metadata);
    }
}
