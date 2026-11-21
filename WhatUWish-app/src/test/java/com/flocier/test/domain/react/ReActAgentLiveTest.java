package com.flocier.test.domain.react;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.summary.SummaryAgent;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class ReActAgentLiveTest {

    @Autowired
    private ReActAgent reActAgent;
    @Autowired
    private SummaryAgent summaryAgent;

    @Test
    public void shouldRunReactAgentThroughRealLlm() {
        String requestId = "react-live-" + UUID.randomUUID();
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .sessionId(requestId)
                .query("请使用工具帮我整理一句包含今天天气很好含义的中文短句。")
                .task("请使用工具帮我整理一句包含今天天气很好含义的中文短句。")
                .maxStep(3)
                .build();

        String reactResult = reActAgent.run(context);
        String finalSummary = summaryAgent.run(context);

        Assert.assertNotNull(reactResult);
        Assert.assertFalse(reactResult.isBlank());
        Assert.assertNotNull(finalSummary);
        Assert.assertFalse(finalSummary.isBlank());
        Assert.assertNotNull(context.getToolExecutionResults());
        Assert.assertFalse(context.getToolExecutionResults().isEmpty());
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
        Assert.assertNotNull(context.getAttribute(SummaryAgent.FINAL_SUMMARY_KEY));
        Assert.assertNotNull(context.getAttribute(ReActAgent.REACT_LAST_PROMPT_KEY));
    }
}
