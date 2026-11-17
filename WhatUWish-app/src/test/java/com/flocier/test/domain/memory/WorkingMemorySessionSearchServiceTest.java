package com.flocier.test.domain.memory;

import com.flocier.domain.agent.runtime.memory.SessionSearchRequest;
import com.flocier.domain.agent.runtime.memory.WorkingMemorySearchMessage;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.SessionSearchTool;
import com.flocier.domain.agent.runtime.memory.SessionSearchService;
import com.flocier.infrastructure.dao.IWorkingMemoryMessageDao;
import com.flocier.infrastructure.memory.WorkingMemorySessionSearchService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 边界测试补足 master READ 截取缺口并验证错误和扫描回退。 */
public class WorkingMemorySessionSearchServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    public void readKeepsAllMessagesUpToThirtyAndTruncatesAfterThat() {
        IWorkingMemoryMessageDao dao = Mockito.mock(IWorkingMemoryMessageDao.class);
        Mockito.when(dao.selectSessionSummary("session")).thenReturn(Map.of("title", "test"));
        WorkingMemorySessionSearchService service = new WorkingMemorySessionSearchService(dao);
        for (int size : new int[]{20, 21, 30, 31}) {
            List<WorkingMemorySearchMessage> rows = new ArrayList<>();
            for (int i = 0; i < size; i++) rows.add(row(i + 1L, "USER", "message " + i));
            Mockito.when(dao.selectHistoryBySession("session")).thenReturn(rows);
            Map<String, Object> result = service.search(SessionSearchRequest.builder().sessionId("session").build());
            List<?> visible = (List<?>) result.get("messages");
            Assert.assertEquals(size > 30 ? 30 : size, visible.size());
            Assert.assertEquals(size > 30, result.get("truncated"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fallbackRetainsAnchorAndRejectsInvalidArguments() {
        IWorkingMemoryMessageDao dao = Mockito.mock(IWorkingMemoryMessageDao.class);
        Mockito.when(dao.selectSessionSummary("session")).thenReturn(Map.of("title", "test"));
        Mockito.when(dao.searchFullText(Mockito.eq("session"), Mockito.eq("Orchid"), Mockito.anyList(), Mockito.anyInt()))
                .thenThrow(new IllegalStateException("FULLTEXT unavailable"));
        Mockito.when(dao.scanHistory(Mockito.eq("session"), Mockito.eq("Orchid"), Mockito.anyList(), Mockito.anyInt()))
                .thenReturn(List.of(row(3L, "USER", "Orchid is ready")));
        WorkingMemorySessionSearchService service = new WorkingMemorySessionSearchService(dao);
        Map<String, Object> result = service.search(SessionSearchRequest.builder()
                .sessionId("session").query("Orchid").build());
        Assert.assertEquals("scan", result.get("search_backend"));
        Assert.assertEquals(3L, ((Map<String, Object>) ((List<?>) result.get("results")).get(0)).get("match_message_id"));
        Assert.assertEquals(false, service.search(SessionSearchRequest.builder()
                .sessionId("session").query("Orchid").aroundMessageId(3L).build()).get("success"));
        Assert.assertEquals(false, service.search(SessionSearchRequest.builder()
                .sessionId("session").limit(0).build()).get("success"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void discoveryDeduplicatesSourcesAndRequiresExplicitToolRole() {
        IWorkingMemoryMessageDao dao = Mockito.mock(IWorkingMemoryMessageDao.class);
        Mockito.when(dao.selectSessionSummary("session")).thenReturn(Map.of("title", "test"));
        WorkingMemorySearchMessage original = row(1L, "USER", "Orchid earlier fact");
        original.setOriginMessageKey("origin-1");
        original.setTurnStatus(2);
        WorkingMemorySearchMessage projection = row(2L, "USER", "Orchid earlier fact");
        projection.setOriginMessageKey("origin-1");
        projection.setTurnSeq(2);
        WorkingMemorySearchMessage tool = row(3L, "TOOL", "Orchid tool result");
        Mockito.when(dao.searchFullText(Mockito.eq("session"), Mockito.eq("Orchid"), Mockito.anyList(), Mockito.anyInt()))
                .thenReturn(List.of(projection, original, tool));
        WorkingMemorySessionSearchService service = new WorkingMemorySessionSearchService(dao);
        Map<String, Object> found = service.search(SessionSearchRequest.builder()
                .sessionId("session").query("Orchid").build());
        Assert.assertEquals(1L, ((Map<String, Object>) ((List<?>) found.get("results")).get(0)).get("match_message_id"));
        Assert.assertEquals("user,assistant", found.get("role_filter"));

        Mockito.when(dao.selectHistoryBySession("session")).thenReturn(List.of(original, tool));
        Assert.assertEquals(1, service.search(SessionSearchRequest.builder()
                .sessionId("session").build()).get("message_count"));
        Assert.assertEquals(2, service.search(SessionSearchRequest.builder()
                .sessionId("session").roleFilter("user,assistant,tool").build()).get("message_count"));
        Mockito.when(dao.selectScrollAnchor("session", 3L)).thenReturn(tool);
        Assert.assertEquals(false, service.search(SessionSearchRequest.builder()
                .sessionId("session").aroundMessageId(3L).build()).get("success"));
    }

    @Test
    public void toolObservationPreservesResultsAndExplainsFailures() {
        SessionSearchService service = request -> Map.of("success", true, "mode", "discover",
                "results", List.of(Map.of("session_id", "old-session", "match_message_id", 42L)), "count", 1);
        SessionSearchTool tool = new SessionSearchTool(service);
        ToolResultPayload found = (ToolResultPayload) tool.execute(ToolInvokeRequest.builder()
                .arguments(Map.of("query", "Orchid")).sessionId("current-session").build(),
                AgentContext.builder().sessionId("current-session").build());
        Assert.assertTrue(found.isSuccess());
        Assert.assertTrue(found.getLlmObservation().contains("old-session"));
        Assert.assertTrue(found.getLlmObservation().contains("match_message_id"));

        ToolResultPayload empty = (ToolResultPayload) new SessionSearchTool(request ->
                Map.of("success", true, "mode", "discover", "results", List.of(), "count", 0))
                .execute(ToolInvokeRequest.builder().arguments(Map.of("query", "missing")).build(), null);
        Assert.assertTrue(empty.isSuccess());
        Assert.assertTrue(empty.getLlmObservation().contains("\"count\":0"));

        ToolResultPayload failed = (ToolResultPayload) new SessionSearchTool(request ->
                Map.of("success", false, "error", "session not found"))
                .execute(ToolInvokeRequest.builder().arguments(Map.of("session_id", "missing")).build(), null);
        Assert.assertFalse(failed.isSuccess());
        Assert.assertTrue(failed.getLlmObservation().contains("session not found"));
    }

    private static WorkingMemorySearchMessage row(long id, String role, String content) {
        WorkingMemorySearchMessage row = new WorkingMemorySearchMessage();
        row.setId(id);
        row.setSessionId("session");
        row.setRequestId("request");
        row.setTurnSeq(1);
        row.setSeqNo((int) id);
        row.setRole(role);
        row.setContent(content);
        return row;
    }
}
