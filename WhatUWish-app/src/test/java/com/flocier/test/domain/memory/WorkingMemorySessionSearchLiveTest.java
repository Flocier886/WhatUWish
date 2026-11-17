package com.flocier.test.domain.memory;

import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/** 真实模型验收：主 ReAct 通过 session_search 找回压缩前历史。 */
@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class WorkingMemorySessionSearchLiveTest {
    @Autowired private SessionWorkingMemoryRepository repository;
    @Autowired private ReactAgentWorkflowExecuteStrategy workflow;
    @Autowired private DataSource dataSource;

    @Test
    public void reactFindsCompactedHistoryThroughTool() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String oldSession = "wm-search-live-old-" + suffix;
        String currentSession = "wm-search-live-current-" + suffix;
        String marker = "翠岚计划";
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.update("INSERT INTO session_ledger (session_id, title, latest_query_text) VALUES (?, ?, ?)",
                    oldSession, "旧记忆工具验收", marker);
            repository.persistTurn(oldSession, "old-" + suffix, suffix, List.of(
                    LlmMessage.builder().role(LlmMessageRole.USER)
                            .content(marker + "的发布窗口定在星期五。请记住这个事实。 ").build(),
                    LlmMessage.builder().role(LlmMessageRole.ASSISTANT)
                            .content("已记录：" + marker + "的发布窗口是星期五。").build()));
            repository.replaceReadyProjection(oldSession, "compact-" + suffix, List.of(
                    LlmMessage.builder().role(LlmMessageRole.USER)
                            .content("[WM_COMPACTION_SUMMARY] 历史已压缩").build()));

            AgentContext context = AgentContext.builder()
                    .requestId("current-" + suffix).sessionId(currentSession)
                    .query("请调用 session_search 找到旧会话中“" + marker + "”的发布窗口，先用 query 发现，"
                            + "再用 session_id 和 around_message_id 阅读命中消息，最后根据历史回答是星期几。")
                    .maxStep(5).build();
            workflow.execute(ExecuteCommandEntity.builder().aiAgentId("react-live")
                    .sessionId(currentSession).message(context.getQuery()).maxStep(5).build(), context);

            long searches = context.getToolExecutionResults().stream()
                    .filter(result -> "session_search".equals(result.getToolName())).count();
            Assert.assertTrue("主 ReAct 未调用 session_search", searches >= 2);
            Assert.assertTrue("模型未消费历史内容", context.getMemory().stream()
                    .filter(message -> message.getRole() == LlmMessageRole.TOOL)
                    .anyMatch(message -> message.getContent() != null
                            && message.getContent().contains(marker)
                            && message.getContent().contains(oldSession)));
            Assert.assertTrue("最终回答未包含旧记忆事实", context.getResult().contains("星期五"));
        } finally {
            jdbc.update("DELETE FROM ai_agent_working_memory_compaction WHERE session_id = ?", oldSession);
            jdbc.update("DELETE FROM ai_agent_working_memory_message WHERE session_id = ?", oldSession);
            jdbc.update("DELETE FROM ai_agent_working_memory_turn WHERE session_id = ?", oldSession);
            jdbc.update("DELETE FROM session_ledger WHERE session_id = ?", oldSession);
        }
    }
}
