package com.flocier.test.domain.memory;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.memory.SessionSearchRequest;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.infrastructure.dao.IWorkingMemoryMessageDao;
import com.flocier.infrastructure.memory.WorkingMemorySessionSearchService;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 使用真实 MySQL 验证 FULLTEXT、INVALID 历史和消息锚点 SQL。 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {SessionWorkingMemoryMysqlTest.Config.class, WorkingMemorySessionSearchService.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
public class WorkingMemorySessionSearchMysqlTest {
    @jakarta.annotation.Resource private SessionWorkingMemoryRepository repository;
    @jakarta.annotation.Resource private WorkingMemorySessionSearchService searchService;
    @jakarta.annotation.Resource private IWorkingMemoryMessageDao messageDao;
    @jakarta.annotation.Resource private DataSource workingMemoryDataSource;

    @Test
    @SuppressWarnings("unchecked")
    public void compactedHistoryCanBeDiscoveredScrolledAndRead() {
        String suffix = UUID.randomUUID().toString();
        String session = "wm-search-" + suffix;
        String unregistered = "wm-search-unregistered-" + suffix;
        JdbcTemplate jdbc = new JdbcTemplate(workingMemoryDataSource);
        try {
            jdbc.update("INSERT INTO session_ledger (session_id, title, latest_query_text) VALUES (?, ?, ?)",
                    session, "旧记忆查询验收", "Orchid 项目约定");
            repository.persistTurn(session, "first-" + suffix, suffix, List.of(
                message(LlmMessageRole.USER, "Orchid project uses an emerald deployment window"),
                message(LlmMessageRole.ASSISTANT, "翡翠部署窗口在星期五。The deployment window is Friday"),
                message(LlmMessageRole.TOOL, "Orchid diagnostic detail")));
            repository.replaceReadyProjection(session, "compact-" + suffix,
                    List.of(message(LlmMessageRole.USER, "[WM_COMPACTION_SUMMARY] prior facts")));
            repository.persistTurn(unregistered, "unregistered-" + suffix, suffix,
                    List.of(message(LlmMessageRole.USER, "Orchid private fixture")));

        String index = jdbc.queryForObject("""
                EXPLAIN SELECT id FROM ai_agent_working_memory_message
                  WHERE MATCH(content) AGAINST ('Orchid' IN NATURAL LANGUAGE MODE)
                """, (rs, rowNum) -> rs.getString("key"));
        Assert.assertEquals("ft_wm_msg_content", index);

        Map<String, Object> discovery = searchService.search(SessionSearchRequest.builder()
                .query("Orchid").sessionId(session).build());
        Assert.assertEquals(true, discovery.get("success"));
        Assert.assertEquals("fulltext", discovery.get("search_backend"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) discovery.get("results");
        Assert.assertEquals(1, results.size());
        Assert.assertEquals(session, results.get(0).get("session_id"));
        Long anchor = ((Number) results.get(0).get("match_message_id")).longValue();

        Map<String, Object> scroll = searchService.search(SessionSearchRequest.builder()
                .sessionId(session).aroundMessageId(anchor).build());
        Assert.assertEquals(true, scroll.get("success"));
        Assert.assertEquals(2, ((List<?>) scroll.get("messages")).size());
        Assert.assertEquals(true, ((Map<?, ?>) ((List<?>) scroll.get("messages")).get(0)).get("anchor"));

        Map<String, Object> read = searchService.search(SessionSearchRequest.builder().sessionId(session).build());
        Assert.assertEquals(true, read.get("success"));
        Assert.assertEquals(2, read.get("message_count"));
        Assert.assertEquals(false, read.get("truncated"));

        Map<String, Object> toolRead = searchService.search(SessionSearchRequest.builder()
                .sessionId(session).roleFilter("user,assistant,tool").build());
        Assert.assertEquals(3, toolRead.get("message_count"));
        Assert.assertEquals(2, messageDao.selectScrollAnchor(session, anchor).getTurnStatus().intValue());
        Map<String, Object> crossSession = searchService.search(SessionSearchRequest.builder()
                .query("Orchid").currentSessionId("other-session").build());
        Assert.assertTrue(((List<Map<String, Object>>) crossSession.get("results")).stream()
                .anyMatch(hit -> session.equals(hit.get("session_id"))));
        Assert.assertFalse(((List<Map<String, Object>>) crossSession.get("results")).stream()
                .anyMatch(hit -> unregistered.equals(hit.get("session_id"))));
        Assert.assertEquals(1, ((List<?>) searchService.search(SessionSearchRequest.builder()
                .sessionId(session).query("部署").build()).get("results")).size());
        Assert.assertEquals(1, messageDao.scanHistory(session, "翡", List.of("ASSISTANT"), 5).size());
        Assert.assertEquals(1, ((List<?>) searchService.search(SessionSearchRequest.builder()
                .sessionId(session).query("diagnostic").roleFilter("tool").build()).get("results")).size());
        Assert.assertEquals(0, ((List<?>) searchService.search(SessionSearchRequest.builder()
                .sessionId(session).query("diagnostic").build()).get("results")).size());
        Assert.assertEquals(0, ((List<?>) searchService.search(SessionSearchRequest.builder()
                .sessionId(session).query("no-such-memory-" + suffix).build()).get("results")).size());
        Assert.assertEquals(false, searchService.search(SessionSearchRequest.builder()
                .sessionId(session).aroundMessageId(Long.MAX_VALUE).build()).get("success"));
        Assert.assertEquals(false, searchService.search(SessionSearchRequest.builder()
                .sessionId("missing-" + suffix).build()).get("success"));
        Assert.assertTrue(((List<Map<String, Object>>) searchService.search(SessionSearchRequest.builder()
                .currentSessionId("other-session").build()).get("results")).stream()
                .anyMatch(hit -> session.equals(hit.get("session_id"))));
        } finally {
            jdbc.update("DELETE FROM ai_agent_working_memory_message WHERE session_id = ?", unregistered);
            jdbc.update("DELETE FROM ai_agent_working_memory_turn WHERE session_id = ?", unregistered);
            jdbc.update("DELETE FROM ai_agent_working_memory_compaction WHERE session_id = ?", session);
            jdbc.update("DELETE FROM ai_agent_working_memory_message WHERE session_id = ?", session);
            jdbc.update("DELETE FROM ai_agent_working_memory_turn WHERE session_id = ?", session);
            jdbc.update("DELETE FROM session_ledger WHERE session_id = ?", session);
        }
    }

    private static LlmMessage message(LlmMessageRole role, String content) {
        return LlmMessage.builder().role(role).content(content).build();
    }
}
