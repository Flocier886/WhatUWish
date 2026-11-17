package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryCompactionEvent;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryProjector;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryMessage;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryTurn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 与 ledger.persistence=memory 配套的工作记忆实现，供无需 MySQL 的运行和定向测试使用。 */
@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemorySessionWorkingMemoryRepository implements SessionWorkingMemoryRepository {
    private final Map<String, List<MemoryTurn>> turns = new HashMap<>();
    private final Set<String> requests = new HashSet<>();
    private final List<WorkingMemoryCompactionEvent> events = new ArrayList<>();
    private final WorkingMemoryProjector projector = new WorkingMemoryProjector();

    /** 保持与 MySQL 适配器一致的 READY 与当前请求过滤语义。 */
    @Override
    public synchronized List<LlmMessage> loadReadyMessages(String sessionId, String currentRequestId) {
        List<LlmMessage> result = new ArrayList<>();
        for (MemoryTurn turn : turns.getOrDefault(sessionId, List.of())) {
            if (turn.status == WorkingMemoryTurn.INVALID || turn.requestId.equals(currentRequestId)) continue;
            if (turn.status == WorkingMemoryTurn.BUILDING) {
                List<WorkingMemoryMessage> rows = projector.project(turn.messages, sessionId, turn.requestId, turn.runId);
                result.addAll(projector.hydrateSafePrefix(rows));
            } else {
                result.addAll(turn.messages);
            }
        }
        return result;
    }

    @Override
    public synchronized boolean hasRequest(String requestId) {
        return requests.contains(requestId);
    }

    @Override
    public synchronized void appendMessage(String sessionId, String segmentId, String runId, int seqNo, LlmMessage message) {
        if (sessionId == null || segmentId == null || message == null || seqNo < 0) {
            throw new IllegalArgumentException("invalid working memory message");
        }
        MemoryTurn turn = find(segmentId);
        if (turn == null) {
            if (seqNo != 0 || !requests.add(segmentId)) throw new IllegalStateException("invalid new memory segment");
            turn = new MemoryTurn(segmentId, runId, new ArrayList<>(), WorkingMemoryTurn.BUILDING);
            turns.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(turn);
        }
        if (!Objects.equals(turn.runId, runId) || !turns.getOrDefault(sessionId, List.of()).contains(turn)) {
            throw new IllegalStateException("working memory segment owner mismatch");
        }
        if (seqNo < turn.messages.size()) {
            LlmMessage old = turn.messages.get(seqNo);
            if (!sameMessage(old, message)) throw new IllegalStateException("working memory message conflict");
            return;
        }
        if (turn.status != WorkingMemoryTurn.BUILDING || seqNo != turn.messages.size()) {
            throw new IllegalStateException("working memory segment is not appendable");
        }
        turn.messages.add(message);
    }

    @Override
    public synchronized void completeSegment(String segmentId) {
        if (segmentId == null) return;
        MemoryTurn turn = find(segmentId);
        if (turn == null || turn.status == WorkingMemoryTurn.INVALID) {
            throw new IllegalStateException("working memory segment cannot complete");
        }
        turn.status = WorkingMemoryTurn.READY;
    }

    /** 以请求标识去重后追加成功轮次。 */
    @Override
    public synchronized boolean persistTurn(String sessionId, String requestId, String runId, List<LlmMessage> messages) {
        if (invalid(sessionId, requestId, messages) || !requests.add(requestId)) return false;
        turns.computeIfAbsent(sessionId, ignored -> new ArrayList<>())
                .add(new MemoryTurn(requestId, runId, new ArrayList<>(messages), WorkingMemoryTurn.READY));
        return true;
    }

    /** 旧轮次保留在内存中，但不再作为 READY 注入。 */
    @Override
    public synchronized boolean replaceReadyProjection(String sessionId, String compactRequestId, List<LlmMessage> messages) {
        if (invalid(sessionId, compactRequestId, messages) || !requests.add(compactRequestId)) return false;
        List<MemoryTurn> sessionTurns = turns.computeIfAbsent(sessionId, ignored -> new ArrayList<>());
        sessionTurns.forEach(turn -> turn.status = WorkingMemoryTurn.INVALID);
        sessionTurns.add(new MemoryTurn(compactRequestId, null, new ArrayList<>(messages), WorkingMemoryTurn.READY));
        return true;
    }

    @Override
    public synchronized void recordCompaction(WorkingMemoryCompactionEvent event) {
        events.add(event);
    }

    private boolean invalid(String sessionId, String requestId, List<LlmMessage> messages) {
        return sessionId == null || sessionId.isBlank() || requestId == null || requestId.isBlank()
                || messages == null || messages.isEmpty();
    }

    private MemoryTurn find(String requestId) {
        for (List<MemoryTurn> sessionTurns : turns.values()) {
            for (MemoryTurn turn : sessionTurns) if (turn.requestId.equals(requestId)) return turn;
        }
        return null;
    }

    private boolean sameMessage(LlmMessage first, LlmMessage second) {
        return first.getRole() == second.getRole()
                && Objects.equals(first.getContent(), second.getContent())
                && Objects.equals(first.getToolCallId(), second.getToolCallId())
                && Objects.equals(first.getToolCalls(), second.getToolCalls());
    }

    private static class MemoryTurn {
        final String requestId;
        final String runId;
        final List<LlmMessage> messages;
        int status;

        MemoryTurn(String requestId, String runId, List<LlmMessage> messages, int status) {
            this.requestId = requestId;
            this.runId = runId;
            this.messages = messages;
            this.status = status;
        }
    }
}
