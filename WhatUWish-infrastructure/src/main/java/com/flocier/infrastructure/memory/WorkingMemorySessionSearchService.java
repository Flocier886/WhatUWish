package com.flocier.infrastructure.memory;

import com.flocier.domain.agent.runtime.memory.SessionSearchRequest;
import com.flocier.domain.agent.runtime.memory.SessionSearchService;
import com.flocier.domain.agent.runtime.memory.WorkingMemorySearchMessage;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryTurn;
import com.flocier.infrastructure.dao.IWorkingMemoryMessageDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 参考 master 的消息级历史浏览；只读 main 工作记忆，包含压缩前 INVALID 轮次。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkingMemorySessionSearchService implements SessionSearchService {
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final int DEFAULT_WINDOW = 5;
    private static final int MAX_WINDOW = 20;
    private static final int MAX_CONTENT = 4000;
    private final IWorkingMemoryMessageDao messages;

    /** 按参数组合选择发现、锚点滚动、会话阅读或近期浏览，并统一校验角色及数量边界。 */
    @Override
    public Map<String, Object> search(SessionSearchRequest request) {
        if (request == null) {
            return browse(null, DEFAULT_LIMIT);
        }
        if (request.getLimit() != null && request.getLimit() < 1) return error("limit must be >= 1");
        if (request.getWindow() != null && request.getWindow() < 1) return error("window must be >= 1");
        String sessionId = trim(request.getSessionId());
        String query = trim(request.getQuery());
        Long anchor = request.getAroundMessageId();
        if (anchor != null && anchor < 1) return error("around_message_id must be >= 1");
        if (anchor != null && sessionId == null) return error("around_message_id requires session_id");
        if (anchor != null && query != null) return error("query cannot be combined with around_message_id");
        List<String> roles = roles(request.getRoleFilter());
        if (roles == null) return error("role_filter must contain user, assistant, tool or system");
        int limit = Math.min(MAX_LIMIT, request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit());
        if (anchor != null) return scroll(sessionId, anchor,
                Math.min(MAX_WINDOW, request.getWindow() == null ? DEFAULT_WINDOW : request.getWindow()), roles);
        if (query != null) return discover(sessionId, trim(request.getCurrentSessionId()), query,
                request.getScope(), limit, roles);
        if (sessionId != null) return read(sessionId, roles);
        return browse(trim(request.getCurrentSessionId()), limit);
    }

    /**
     * 关键词定位消息锚点：优先 FULLTEXT，查询执行异常时回退扫描。
     * 跨会话结果限定在 session_ledger 已登记的本地会话；显式会话必须先通过登记检查。
     */
    private Map<String, Object> discover(String requestedSession, String currentSession, String query,
                                         String scope, int limit, List<String> roles) {
        boolean oneSession = requestedSession != null || "session".equalsIgnoreCase(scope);
        String sessionId = requestedSession != null ? requestedSession : oneSession ? currentSession : null;
        if (oneSession && sessionId == null) return error("session_id is required for scope=session");
        if (oneSession && !registered(sessionId)) return error("session_id " + sessionId + " not found");
        List<String> tokens = List.of(query.split("\\s+"));
        int scanLimit = Math.min(100, limit * 4);
        List<WorkingMemorySearchMessage> found;
        String backend = "fulltext";
        try {
            found = messages.searchFullText(sessionId, query, roles, scanLimit);
        } catch (Exception fullTextError) {
            log.warn("working-memory FULLTEXT unavailable, falling back to scan: {}", fullTextError.toString());
            backend = "scan";
            try {
                found = messages.scanHistory(sessionId, query, roles, scanLimit);
            } catch (Exception scanError) {
                return error("failed to search message history: " + messageOf(scanError));
            }
        }
        // 压缩后的重投影可能保留相同来源键；选最早的原消息作为稳定锚点。
        Map<String, WorkingMemorySearchMessage> uniqueOrigins = new LinkedHashMap<>();
        for (WorkingMemorySearchMessage hit : safe(found)) {
            if (hit == null || !matches(hit.getContent(), tokens) || summary(hit.getContent())) continue;
            if (!oneSession && currentSession != null && currentSession.equals(hit.getSessionId())
                    && !Integer.valueOf(WorkingMemoryTurn.INVALID).equals(hit.getTurnStatus())) continue;
            String origin = StringUtils.hasText(hit.getOriginMessageKey()) ? hit.getOriginMessageKey() : hit.stableKey();
            String key = hit.getSessionId() + ":" + origin;
            WorkingMemorySearchMessage previous = uniqueOrigins.get(key);
            if (previous == null || historyOrder().compare(hit, previous) < 0) uniqueOrigins.put(key, hit);
        }
        // 发现模式每个会话只给一个轻量命中，正文由后续 scroll/read 按需读取。
        Map<String, WorkingMemorySearchMessage> sessions = new LinkedHashMap<>();
        for (WorkingMemorySearchMessage hit : uniqueOrigins.values()) {
            sessions.putIfAbsent(hit.getSessionId(), hit);
            if (sessions.size() >= limit) break;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (WorkingMemorySearchMessage hit : sessions.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("session_id", hit.getSessionId());
            item.put("match_message_id", hit.getId());
            item.put("origin_message_key", hit.getOriginMessageKey());
            item.put("stable_key", hit.stableKey());
            item.put("matched_role", hit.getRole());
            item.put("snippet", snippet(hit.getContent(), tokens));
            item.put("matched_terms", tokens);
            if (hit.getSearchScore() != null) item.put("search_score", hit.getSearchScore());
            metadata(item, hit.getSessionId());
            results.add(item);
        }
        Map<String, Object> out = success("discover");
        out.put("query", query);
        out.put("role_filter", String.join(",", roles).toLowerCase(Locale.ROOT));
        out.put("results", results);
        out.put("count", results.size());
        out.put("search_backend", backend);
        return out;
    }

    /** 校验锚点归属和可见角色，再按历史顺序拼接锚点前后的受限消息窗口。 */
    private Map<String, Object> scroll(String sessionId, long anchorId, int window, List<String> roles) {
        if (!registered(sessionId)) return error("session_id " + sessionId + " not found");
        try {
            WorkingMemorySearchMessage anchor = messages.selectScrollAnchor(sessionId, anchorId);
            if (anchor == null) return error("around_message_id " + anchorId + " not found in session_id " + sessionId);
            if (!roles.contains(anchor.getRole()) || summary(anchor.getContent()))
                return error("around_message_id " + anchorId + " is hidden by role_filter");
            List<WorkingMemorySearchMessage> before = visible(messages.selectScrollBefore(sessionId, anchorId, roles, window), roles);
            List<WorkingMemorySearchMessage> after = visible(messages.selectScrollAfter(sessionId, anchorId, roles, window), roles);
            before.sort(historyOrder());
            List<WorkingMemorySearchMessage> selected = new ArrayList<>(before);
            selected.add(anchor);
            selected.addAll(after);
            Map<String, Object> out = success("scroll");
            out.put("session_id", sessionId);
            out.put("around_message_id", anchorId);
            out.put("window", window);
            out.put("role_filter", String.join(",", roles).toLowerCase(Locale.ROOT));
            out.put("messages", shape(selected, anchorId));
            out.put("messages_before", before.size());
            out.put("messages_after", after.size());
            metadata(out, sessionId);
            return out;
        } catch (Exception e) {
            return error("failed to load messages: " + messageOf(e));
        }
    }

    /** 小会话完整阅读；超过 30 条时只返回前 20 条和后 10 条，提示模型用锚点补读中段。 */
    private Map<String, Object> read(String sessionId, List<String> roles) {
        if (!registered(sessionId)) return error("session_id " + sessionId + " not found");
        try {
            List<WorkingMemorySearchMessage> history = visible(messages.selectHistoryBySession(sessionId), roles);
            int size = history.size();
            boolean truncated = size > 30;
            List<WorkingMemorySearchMessage> selected = new ArrayList<>();
            if (truncated) {
                selected.addAll(history.subList(0, 20));
                selected.addAll(history.subList(size - 10, size));
            } else {
                selected.addAll(history);
            }
            Map<String, Object> out = success("read");
            out.put("session_id", sessionId);
            out.put("role_filter", String.join(",", roles).toLowerCase(Locale.ROOT));
            out.put("message_count", size);
            out.put("truncated", truncated);
            out.put("messages", shape(selected, null));
            if (truncated) out.put("message", "Showing first 20 and last 10 messages; use around_message_id to read the middle.");
            metadata(out, sessionId);
            return out;
        } catch (Exception e) {
            return error("failed to load session: " + messageOf(e));
        }
    }

    /** 浏览有主工作记忆的近期会话，只提供元信息，不展开消息正文。 */
    private Map<String, Object> browse(String currentSession, int limit) {
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> row : messages.selectRecentSessions(limit + 1)) {
                String sessionId = String.valueOf(row.get("sessionId"));
                if (sessionId.equals(currentSession)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("session_id", sessionId);
                item.put("title", row.get("title"));
                Object query = row.get("latestQueryText");
                item.put("preview", query == null ? row.get("latestSummaryText") : query);
                item.put("started_at", row.get("startedAt"));
                item.put("last_active_at", row.get("lastActiveAt"));
                item.put("message_count", row.get("messageCount"));
                results.add(item);
                if (results.size() >= limit) break;
            }
            Map<String, Object> out = success("browse");
            out.put("results", results);
            out.put("count", results.size());
            return out;
        } catch (Exception e) {
            return error("failed to browse sessions: " + messageOf(e));
        }
    }

    /** 显式访问要求会话已登记，且确有未删除的主工作记忆消息。 */
    private boolean registered(String sessionId) {
        return sessionId != null && messages.selectSessionSummary(sessionId) != null;
    }

    private void metadata(Map<String, Object> target, String sessionId) {
        try {
            Map<String, Object> row = messages.selectSessionSummary(sessionId);
            if (row == null) return;
            target.put("title", row.get("title"));
            target.put("started_at", row.get("startedAt"));
            target.put("last_active_at", row.get("lastActiveAt"));
        } catch (Exception e) {
            log.debug("session metadata lookup failed: {}", e.toString());
        }
    }

    /** 对 READ/scroll 的查询结果统一应用角色过滤，并隐藏压缩摘要。 */
    private static List<WorkingMemorySearchMessage> visible(List<WorkingMemorySearchMessage> source, List<String> roles) {
        List<WorkingMemorySearchMessage> result = new ArrayList<>();
        for (WorkingMemorySearchMessage row : safe(source)) {
            if (row != null && roles.contains(row.getRole()) && !summary(row.getContent())) result.add(row);
        }
        return result;
    }

    /** 将数据库行裁成模型可见字段，并对单条正文设置长度上限。 */
    private static List<Map<String, Object>> shape(List<WorkingMemorySearchMessage> rows, Long anchor) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkingMemorySearchMessage row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("stable_key", row.stableKey());
            item.put("role", row.getRole());
            String content = row.getContent() == null ? "" : row.getContent();
            item.put("content", content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) + "..." : content);
            if (content.length() > MAX_CONTENT) {
                item.put("content_truncated", true);
                item.put("original_content_chars", content.length());
            }
            item.put("seq_no", row.getSeqNo());
            if (row.getId().equals(anchor)) item.put("anchor", true);
            result.add(item);
        }
        return result;
    }

    private static String snippet(String content, List<String> tokens) {
        if (content == null) return "";
        int pos = -1;
        String lower = content.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            pos = lower.indexOf(token.toLowerCase(Locale.ROOT));
            if (pos >= 0) break;
        }
        int start = Math.max(0, pos - 100);
        int end = Math.min(content.length(), start + 280);
        return (start > 0 ? "..." : "") + content.substring(start, end) + (end < content.length() ? "..." : "");
    }

    /** FULLTEXT 只负责候选召回；最终要求正文包含查询中的每个词。 */
    private static boolean matches(String content, List<String> tokens) {
        if (content == null) return false;
        String lower = content.toLowerCase(Locale.ROOT);
        return tokens.stream().allMatch(token -> lower.contains(token.toLowerCase(Locale.ROOT)));
    }

    private static boolean summary(String content) {
        if (content == null) return false;
        String text = content.stripLeading();
        return text.startsWith("[CONTEXT COMPACTION") || text.startsWith("[CONTEXT SUMMARY]:")
                || text.startsWith("[COMPACTION SUMMARY]") || text.startsWith("[WM_COMPACTION_SUMMARY]");
    }

    private static List<String> roles(String filter) {
        if (!StringUtils.hasText(filter)) return List.of("USER", "ASSISTANT");
        List<String> result = new ArrayList<>();
        for (String role : filter.split(",")) {
            String normalized = role.trim().toUpperCase(Locale.ROOT);
            if (!List.of("USER", "ASSISTANT", "TOOL", "SYSTEM").contains(normalized)) return null;
            if (!result.contains(normalized)) result.add(normalized);
        }
        return result.isEmpty() ? null : result;
    }

    private static Comparator<WorkingMemorySearchMessage> historyOrder() {
        return Comparator.comparing(WorkingMemorySearchMessage::getTurnSeq)
                .thenComparing(WorkingMemorySearchMessage::getSeqNo)
                .thenComparing(WorkingMemorySearchMessage::getId);
    }

    private static List<WorkingMemorySearchMessage> safe(List<WorkingMemorySearchMessage> value) {
        return value == null ? Collections.emptyList() : value;
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String messageOf(Exception e) {
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static Map<String, Object> success(String mode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mode", mode);
        return result;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }
}
