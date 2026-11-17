package com.flocier.domain.agent.runtime.agent.react;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.plan.ReactThinkResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolRunner;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class ReActObservationIsolationTest {

    @Test
    public void shouldKeepEachToolObservationAndCallIdWithinTheSameReactStep() throws Exception {
        ReActAgent agent = new ReActAgent();
        setField(agent, "toolRunner", controlledRunner());
        AgentContext context = AgentContext.builder()
                .requestId("request-observation")
                .sessionId("session-observation")
                .traceId("trace-observation")
                .currentStep(2)
                .build();
        context.setToolCollection(new ToolCollection(List.of()));
        context.setAttribute(ReActAgent.REACT_TRACE_KEY, new ArrayList<String>());

        agent.act(context, ReactThinkResult.builder().toolCalls(List.of(
                toolCall("call-search", "web_search"),
                toolCall("call-read", "artifact_read")
        )).build());

        Assert.assertTrue(context.getLastObservation().contains("[call-search] web_search: search observation"));
        Assert.assertTrue(context.getLastObservation().contains("[call-read] artifact_read: read observation"));
        Assert.assertEquals(2, context.getToolExecutionResults().size());
        List<LlmMessage> toolMessages = context.getMemory().stream()
                .filter(message -> message.getRole() == LlmMessageRole.TOOL)
                .toList();
        Assert.assertEquals(2, toolMessages.size());
        Assert.assertEquals("call-search", toolMessages.get(0).getToolCallId());
        Assert.assertEquals("search observation", toolMessages.get(0).getContent());
        Assert.assertEquals("call-read", toolMessages.get(1).getToolCallId());
        Assert.assertEquals("read observation", toolMessages.get(1).getContent());
    }

    private ToolRunner controlledRunner() {
        return (toolCollection, request, context) -> ToolExecutionResult.builder()
                .toolName(request.getToolName())
                .toolCallId(request.getToolCallId())
                .success(true)
                .status("success")
                .llmObservation("web_search".equals(request.getToolName()) ? "search observation" : "read observation")
                .build();
    }

    private ToolCall toolCall(String id, String toolName) {
        return ToolCall.builder().id(id)
                .function(ToolCall.Function.builder().name(toolName).arguments("{}").build())
                .build();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
