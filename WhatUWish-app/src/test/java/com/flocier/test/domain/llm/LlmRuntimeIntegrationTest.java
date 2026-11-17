package com.flocier.test.domain.llm;

import com.flocier.config.LlmRuntimeAutoConfiguration;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.LlmRuntimeDependencies;
import com.flocier.domain.agent.runtime.llm.LLM;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = {
                ReactorConfig.class,
                LlmRuntimeAutoConfiguration.class,
                LlmRuntimeDependencies.class,
                LlmExecutor.class,
                LLM.class,
                OpenAiChatOptionsFactory.class,
                DomainMessageConverter.class,
                LlmResponseMapper.class
        },
        properties = {
                "reactor.llm.settings={\"defaults\":{\"model\":\"default-chat\"},\"behavior\":{\"extractUsage\":true,\"logRawResponse\":false,\"enableFallback\":false},\"providers\":{\"default-openai\":{\"baseUrl\":\"https://api.example.com\",\"apiKey\":\"test-key\",\"interfacePath\":\"/v1/chat/completions\"}},\"models\":{\"default-chat\":{\"provider\":\"default-openai\",\"model\":\"gpt-4o-mini\",\"temperature\":0.7,\"maxTokens\":2048}}}"
        }
)
public class LlmRuntimeIntegrationTest {

    @Autowired
    private LLM llm;

    @Autowired
    private LlmSettingsRegistry llmSettingsRegistry;

    @MockitoBean
    private LlmModelResolver llmModelResolver;

    @Test
    public void shouldRunFullRuntimeChainFromSpringContext() {
        OpenAiChatModel chatModel = Mockito.mock(OpenAiChatModel.class);
        when(llmModelResolver.resolve(any(LlmResolvedModel.class))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(buildChatResponse());

        LlmResponse response = llm.call(LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.builder()
                                .role(LlmMessageRole.USER)
                                .content("say something for integration test")
                                .build()
                ))
                .build());

        assertNotNull(llm);
        assertNotNull(llmSettingsRegistry);
        assertNotNull(response);
        assertEquals("gpt-4o-mini", llmSettingsRegistry.resolve(null).getConfig().getModel());
        assertEquals("gpt-4o-mini", response.getModel());
        assertEquals("default-openai", response.getProvider());
        assertEquals("integration reply", response.getText());
        assertEquals("stop", response.getFinishReason());
        assertEquals(Integer.valueOf(12), response.getUsage().getPromptTokens());
        assertEquals(Integer.valueOf(18), response.getUsage().getCompletionTokens());
        assertEquals(Integer.valueOf(30), response.getUsage().getTotalTokens());
    }

    private ChatResponse buildChatResponse() {
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 12;
            }

            @Override
            public Integer getCompletionTokens() {
                return 18;
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
                .id("chatcmpl-integration")
                .model("gpt-4o-mini")
                .usage(usage)
                .build();

        Generation generation = new Generation(
                new AssistantMessage("integration reply"),
                ChatGenerationMetadata.builder()
                        .finishReason("stop")
                        .build()
        );

        return new ChatResponse(List.of(generation), metadata);
    }
}
