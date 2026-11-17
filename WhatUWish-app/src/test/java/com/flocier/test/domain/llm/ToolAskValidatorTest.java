package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskValidationResult;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskValidator;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ToolAskValidatorTest {

    private final ToolAskValidator validator = new ToolAskValidator();

    @Test
    public void shouldRejectUnknownToolName() {
        ToolAskRequest request = ToolAskRequest.builder()
                .toolDefinitions(Map.of(
                        "echo_tool", Map.of(
                                "params", Map.of(
                                        "type", "object",
                                        "properties", Map.of("message", Map.of("type", "string"))
                                )
                        )
                ))
                .build();

        ToolAskResponse response = ToolAskResponse.builder()
                .completed(false)
                .toolCalls(List.of(
                        ToolCall.builder()
                                .id("call-1")
                                .type("function")
                                .function(ToolCall.Function.builder()
                                        .name("unknown_tool")
                                        .arguments("{\"message\":\"hello\"}")
                                        .build())
                                .build()
                ))
                .build();

        ToolAskValidationResult result = validator.validate(request, response);

        Assert.assertFalse(result.isValid());
        Assert.assertTrue(result.getReason().contains("tool name is not allowed"));
    }

    @Test
    public void shouldRejectUnknownToolArgument() {
        ToolAskRequest request = ToolAskRequest.builder()
                .toolDefinitions(Map.of(
                        "echo_tool", Map.of(
                                "params", Map.of(
                                        "type", "object",
                                        "properties", Map.of("message", Map.of("type", "string")),
                                        "required", List.of("message")
                                )
                        )
                ))
                .build();

        ToolAskResponse response = ToolAskResponse.builder()
                .completed(false)
                .toolCalls(List.of(
                        ToolCall.builder()
                                .id("call-1")
                                .type("function")
                                .function(ToolCall.Function.builder()
                                        .name("echo_tool")
                                        .arguments("{\"badField\":\"hello\"}")
                                        .build())
                                .build()
                ))
                .build();

        ToolAskValidationResult result = validator.validate(request, response);

        Assert.assertFalse(result.isValid());
        Assert.assertTrue(result.getReason().contains("tool argument is not allowed"));
    }
}
