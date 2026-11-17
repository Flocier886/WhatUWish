package com.flocier.test.domain.react;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.react.ReActToolBatchExecutor;
import com.flocier.domain.agent.runtime.agent.react.plan.ReactThinkResult;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.PreparedToolExecution;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolRunner;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.infrastructure.persistent.memory.InMemorySessionWorkingMemoryRepository;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** 验证 ReAct 只并发执行工具业务，模型可见上下文仍按原 Tool Call 顺序合并。 */
public class ReActAgentToolBatchConcurrencyTest {

    @Test
    public void shouldSerializeBatchContainingCodeExecution() {
        BatchToolRunner runner = new BatchToolRunner();
        TestableReActAgent agent = new TestableReActAgent();
        ReflectionTestUtils.setField(agent, "toolRunner", runner);
        ReflectionTestUtils.setField(agent, "toolBatchExecutor", new ReActToolBatchExecutor());
        AgentContext context = AgentContext.builder()
                .requestId("request-code").sessionId("session-code").currentStep(1).build();
        context.setToolCollection(new ToolCollection(List.of()));
        context.setAttribute(ReActAgent.REACT_TRACE_KEY, new ArrayList<String>());
        ReactThinkResult thinkResult = ReactThinkResult.builder()
                .toolCalls(List.of(call("call-first", "slow_tool"), call("call-code", "code_execution"),
                        call("call-last", "fast_tool")))
                .build();

        agent.invokeAct(context, thinkResult);

        Assert.assertEquals(1, runner.maxConcurrent.get());
        Assert.assertEquals(List.of("call-first", "call-code", "call-last"), runner.completionOrder);
    }

    @Test
    public void shouldExecuteBatchConcurrentlyButMergeObservationsInModelOrder() {
        BatchToolRunner runner = new BatchToolRunner();
        TestableReActAgent agent = new TestableReActAgent();
        ReflectionTestUtils.setField(agent, "toolRunner", runner);
        ReflectionTestUtils.setField(agent, "toolBatchExecutor", new ReActToolBatchExecutor());
        InMemorySessionWorkingMemoryRepository memory = new InMemorySessionWorkingMemoryRepository();
        ReflectionTestUtils.setField(agent, "workingMemoryRepository", memory);
        ReflectionTestUtils.setField(agent, "workingMemoryEnabled", true);

        AgentContext context = AgentContext.builder()
                .requestId("request-batch").sessionId("session-batch").currentStep(1).build();
        context.setToolCollection(new ToolCollection(List.of()));
        context.setAttribute(ReActAgent.REACT_TRACE_KEY, new ArrayList<String>());
        ReactThinkResult thinkResult = ReactThinkResult.builder()
                .toolCalls(List.of(call("call-first", "slow_tool"), call("call-second", "fast_tool")))
                .build();
        memory.appendMessage("session-batch", "request-batch", null, 0,
                com.flocier.domain.agent.runtime.llm.message.LlmMessage.builder()
                        .role(LlmMessageRole.ASSISTANT).toolCalls(thinkResult.getToolCalls()).build());
        context.setAttribute("workingMemory.segmentId", "request-batch");
        context.setAttribute("workingMemory.nextSeq", 1);

        agent.invokeAct(context, thinkResult);

        Assert.assertTrue("同批独立工具应在 worker 中重叠执行", runner.maxConcurrent.get() >= 2);
        Assert.assertEquals(List.of("call-second", "call-first"), runner.completionOrder);
        Assert.assertEquals(List.of("call-first", "call-second"), context.getMemory().stream()
                .filter(message -> message.getRole() == LlmMessageRole.TOOL)
                .map(message -> message.getToolCallId()).toList());
        Assert.assertEquals(List.of("call-first", "call-second"), memory.loadReadyMessages("session-batch", "later").stream()
                .filter(message -> message.getRole() == LlmMessageRole.TOOL)
                .map(message -> message.getToolCallId()).toList());
        Assert.assertTrue(context.getLastObservation().indexOf("[call-first]")
                < context.getLastObservation().indexOf("[call-second]"));
        Assert.assertEquals(List.of("call-first", "call-second"), context.getToolExecutionResults().stream()
                .map(ToolExecutionResult::getToolCallId).toList());
    }

    @Test
    public void shouldKeepOtherCallsAndArtifactsIsolatedWhenOneConcurrentCallFails() {
        BatchToolRunner runner = new BatchToolRunner();
        TestableReActAgent agent = new TestableReActAgent();
        ReflectionTestUtils.setField(agent, "toolRunner", runner);
        ReflectionTestUtils.setField(agent, "toolBatchExecutor", new ReActToolBatchExecutor());

        AgentContext context = AgentContext.builder()
                .requestId("request-batch-failure").sessionId("session-batch-failure").currentStep(1).build();
        context.setToolCollection(new ToolCollection(List.of()));
        context.setAttribute(ReActAgent.REACT_TRACE_KEY, new ArrayList<String>());
        ReactThinkResult thinkResult = ReactThinkResult.builder()
                .toolCalls(List.of(
                        call("call-failed", "failing_tool"),
                        call("call-artifact-a", "slow_artifact_tool"),
                        call("call-artifact-b", "fast_artifact_tool")
                ))
                .build();

        agent.invokeAct(context, thinkResult);

        Assert.assertTrue("失败调用不应阻止同批其他工具并发执行", runner.maxConcurrent.get() >= 2);
        Assert.assertEquals(List.of("call-failed", "call-artifact-a", "call-artifact-b"),
                context.getMemory().stream()
                        .filter(message -> message.getRole() == LlmMessageRole.TOOL)
                        .map(message -> message.getToolCallId())
                        .toList());

        Map<String, ToolExecutionResult> resultsByCall = context.getToolExecutionResults().stream()
                .collect(Collectors.toMap(ToolExecutionResult::getToolCallId, item -> item));
        Assert.assertFalse(resultsByCall.get("call-failed").isSuccess());
        Assert.assertEquals("tool_execute_error", resultsByCall.get("call-failed").getErrorCode());
        Assert.assertTrue(resultsByCall.get("call-artifact-a").isSuccess());
        Assert.assertTrue(resultsByCall.get("call-artifact-b").isSuccess());
        Assert.assertEquals(List.of("artifact-call-artifact-a"),
                resultsByCall.get("call-artifact-a").getArtifactRefs());
        Assert.assertEquals(List.of("artifact-call-artifact-b"),
                resultsByCall.get("call-artifact-b").getArtifactRefs());
        Assert.assertTrue(context.getLastObservation().indexOf("[call-failed]")
                < context.getLastObservation().indexOf("[call-artifact-a]"));
        Assert.assertTrue(context.getLastObservation().indexOf("[call-artifact-a]")
                < context.getLastObservation().indexOf("[call-artifact-b]"));
    }

    private static ToolCall call(String id, String toolName) {
        return ToolCall.builder().id(id).function(ToolCall.Function.builder().name(toolName).arguments("{}").build()).build();
    }

    private static final class TestableReActAgent extends ReActAgent {
        private void invokeAct(AgentContext context, ReactThinkResult thinkResult) {
            super.act(context, thinkResult);
        }
    }

    private static final class BatchToolRunner implements ToolRunner {
        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final List<String> completionOrder = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public ToolExecutionResult run(ToolCollection toolCollection, ToolInvokeRequest request, AgentContext context) {
            return execute(prepare(toolCollection, request, context), context);
        }

        @Override
        public PreparedToolExecution prepare(ToolCollection toolCollection, ToolInvokeRequest request, AgentContext context) {
            return PreparedToolExecution.ready(request, new TestTool(request.getToolName()), null, Instant.now());
        }

        @Override
        public ToolExecutionResult execute(PreparedToolExecution prepared, AgentContext context) {
            int active = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(active, Math::max);
            try {
                if ("slow_tool".equals(prepared.getRequest().getToolName())) {
                    Thread.sleep(120L);
                } else if ("slow_artifact_tool".equals(prepared.getRequest().getToolName())) {
                    Thread.sleep(100L);
                } else if ("failing_tool".equals(prepared.getRequest().getToolName())) {
                    Thread.sleep(20L);
                    throw new IllegalStateException("simulated tool failure");
                } else {
                    Thread.sleep(20L);
                }
                completionOrder.add(prepared.getRequest().getToolCallId());
                return ToolExecutionResult.builder()
                        .toolName(prepared.getRequest().getToolName())
                        .toolCallId(prepared.getRequest().getToolCallId())
                        .success(true).status("success")
                        .llmObservation("observation-" + prepared.getRequest().getToolCallId())
                        .arguments(Map.of()).metadata(Map.of())
                        .artifactRefs(prepared.getRequest().getToolName().contains("artifact")
                                ? List.of("artifact-" + prepared.getRequest().getToolCallId())
                                : List.of())
                        .ledgerFacts(List.of()).attributes(Map.of())
                        .startedAt(prepared.getStartedAt()).finishedAt(Instant.now()).durationMs(1L)
                        .build();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }

    private record TestTool(String name) implements BaseTool {
        @Override public String description() { return name; }
        @Override public Object params() { return Map.of(); }
        @Override public Object execute(Object input, AgentContext context) { return null; }
    }
}
