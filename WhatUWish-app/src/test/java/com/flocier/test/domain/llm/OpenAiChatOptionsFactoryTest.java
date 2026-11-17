package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.options.OpenAiChatOptionsFactory;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import org.junit.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class OpenAiChatOptionsFactoryTest {

    @Test
    public void shouldBuildOptionsWithRequestOverridesAndExtraBody() {
        Map<String, Object> extParams = new LinkedHashMap<>();
        extParams.put("top_p", 0.8);
        extParams.put("presence_penalty", 0.3);
        extParams.put("stop", List.of("DONE"));
        extParams.put("custom_flag", "enabled");

        LlmResolvedModel resolvedModel = LlmResolvedModel.builder()
                .alias("default-chat")
                .config(LlmModelConfig.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .maxTokens(2048)
                        .extParams(extParams)
                        .build())
                .build();

        LlmRequest request = LlmRequest.builder()
                .temperature(0.2)
                .maxTokens(128)
                .build();

        OpenAiChatOptions options = new OpenAiChatOptionsFactory().build(resolvedModel, request);

        assertEquals("gpt-4o-mini", options.getModel());
        assertEquals(Double.valueOf(0.2), options.getTemperature());
        assertEquals(Integer.valueOf(128), options.getMaxTokens());
        assertEquals(Double.valueOf(0.8), options.getTopP());
        assertEquals(Double.valueOf(0.3), options.getPresencePenalty());
        assertEquals(List.of("DONE"), options.getStop());
        assertEquals("enabled", options.getExtraBody().get("custom_flag"));
    }

    @Test
    public void shouldBuildToolCallOptionsWithInternalExecutionDisabled() {
        LlmResolvedModel resolvedModel = LlmResolvedModel.builder()
                .alias("default-chat")
                .config(LlmModelConfig.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .maxTokens(2048)
                        .build())
                .build();

        ToolAskRequest request = ToolAskRequest.builder()
                .temperature(0.2)
                .maxTokens(128)
                .toolDefinitions(Map.of(
                        "echo_tool", Map.of(
                                "name", "echo_tool",
                                "description", "echo input",
                                "params", Map.of(
                                        "type", "object",
                                        "properties", Map.of("message", Map.of("type", "string"))
                                )
                        )
                ))
                .build();

        OpenAiChatOptions options = new OpenAiChatOptionsFactory().buildToolCallOptions(resolvedModel, request);

        assertEquals("gpt-4o-mini", options.getModel());
        assertEquals(Double.valueOf(0.2), options.getTemperature());
        assertEquals(Integer.valueOf(128), options.getMaxTokens());
        assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
        assertEquals(Boolean.FALSE, options.getParallelToolCalls());
        assertEquals(1, options.getTools().size());
        assertEquals("echo_tool", options.getTools().get(0).getFunction().getName());
    }
}
