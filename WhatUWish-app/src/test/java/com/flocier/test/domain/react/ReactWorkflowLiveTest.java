package com.flocier.test.domain.react;

import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.summary.SummaryAgent;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class ReactWorkflowLiveTest {

    @Autowired
    private ReactAgentWorkflowExecuteStrategy reactAgentWorkflowExecuteStrategy;
    @Autowired
    private SessionWorkingMemoryRepository workingMemoryRepository;

    @Test
    public void shouldRunReactWorkflowThroughRealLlm() throws Exception {
        List<Object> outputs = new ArrayList<>();
        String requestId = "react-workflow-live-" + UUID.randomUUID();
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .sessionId(requestId)
                .query("请调用工具整理一句简短中文结论，再输出最终回答。")
                .task("请调用工具整理一句简短中文结论，再输出最终回答。")
                .maxStep(3)
                .resultWriter(outputs::add)
                .build();

        ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                .aiAgentId("react-live")
                .message(context.getTask())
                .sessionId(context.getSessionId())
                .maxStep(context.getMaxStep())
                .build();

        reactAgentWorkflowExecuteStrategy.execute(command, context);

        Assert.assertNotNull(context.getAttribute(ReActAgent.REACT_RESULT_KEY));
        Assert.assertNotNull(context.getAttribute(SummaryAgent.FINAL_SUMMARY_KEY));
        Assert.assertNotNull(context.getFinishReason());
        Assert.assertTrue(context.getMemory().stream().anyMatch(message ->
                message.getRole() == LlmMessageRole.ASSISTANT
                        && message.getToolCalls() != null
                        && !message.getToolCalls().isEmpty()));
        Assert.assertTrue(context.getMemory().stream().anyMatch(message ->
                message.getRole() == LlmMessageRole.TOOL
                        && message.getToolCallId() != null
                        && !message.getToolCallId().isBlank()));
        List<String> trace = context.getAttribute(ReActAgent.REACT_TRACE_KEY);
        Assert.assertNotNull(trace);
        Assert.assertFalse(trace.isEmpty());
        Assert.assertFalse(outputs.isEmpty());
        var persisted = workingMemoryRepository.loadReadyMessages(requestId, "later-request");
        Assert.assertEquals(context.getReactMemorySnapshot().size(), persisted.size());
        Assert.assertEquals(context.getMemory().stream()
                        .filter(message -> message.getRole() == LlmMessageRole.TOOL)
                        .map(message -> message.getToolCallId()).toList(),
                persisted.stream().filter(message -> message.getRole() == LlmMessageRole.TOOL)
                        .map(message -> message.getToolCallId()).toList());
    }
}
