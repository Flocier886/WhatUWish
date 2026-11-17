package com.flocier.infrastructure.adapter.repository;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryCompactionEvent;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryMessage;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryProjector;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryTurn;
import com.flocier.infrastructure.dao.IWorkingMemoryCompactionDao;
import com.flocier.infrastructure.dao.IWorkingMemoryMessageDao;
import com.flocier.infrastructure.dao.IWorkingMemoryTurnDao;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** MySQL 工作记忆适配器；轮次追加及 READY 投影替换分别在单个本地事务中完成。 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "mysql")
public class MysqlSessionWorkingMemoryRepository implements SessionWorkingMemoryRepository {
    private final IWorkingMemoryTurnDao turnDao;
    private final IWorkingMemoryMessageDao messageDao;
    private final IWorkingMemoryCompactionDao compactionDao;
    private final WorkingMemoryProjector projector;

    /** 按轮次顺序恢复 READY 消息，跳过当前请求已存在的轮次。 */
    @Override
    public List<LlmMessage> loadReadyMessages(String sessionId, String currentRequestId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        List<WorkingMemoryTurn> turns = turnDao.selectLoadable(sessionId).stream()
                .filter(turn -> !turn.getRequestId().equals(currentRequestId)).toList();
        if (turns.isEmpty()) return List.of();
        List<WorkingMemoryMessage> rows = messageDao.selectByTurnIds(turns.stream().map(WorkingMemoryTurn::getId).toList());
        Map<Long, List<WorkingMemoryMessage>> byTurn = rows.stream()
                .collect(Collectors.groupingBy(WorkingMemoryMessage::getTurnId));
        List<LlmMessage> messages = new ArrayList<>();
        for (WorkingMemoryTurn turn : turns) {
            List<WorkingMemoryMessage> part = byTurn.getOrDefault(turn.getId(), List.of());
            messages.addAll(turn.getStatus() == WorkingMemoryTurn.BUILDING
                    ? projector.hydrateSafePrefix(part) : projector.hydrate(part));
        }
        return messages;
    }

    @Override
    public boolean hasRequest(String requestId) {
        return requestId != null && turnDao.selectByRequestId(requestId) != null;
    }

    /** 首次创建 BUILDING，后续按序号追加；消息与计数在同一事务提交。 */
    @Override
    @Transactional
    public void appendMessage(String sessionId, String segmentId, String runId, int seqNo, LlmMessage message) {
        if (invalid(sessionId, segmentId, List.of(message)) || seqNo < 0) {
            throw new IllegalArgumentException("invalid working memory message");
        }
        WorkingMemoryTurn turn = turnDao.selectByRequestId(segmentId);
        if (turn == null) {
            if (seqNo != 0) throw new IllegalStateException("working memory segment must start at zero");
            turn = new WorkingMemoryTurn();
            turn.setSessionId(sessionId);
            turn.setRequestId(segmentId);
            turn.setRunId(runId);
            turn.setTurnSeq(nextSeq(sessionId));
            turn.setEntryAgent("react");
            turn.setStatus(WorkingMemoryTurn.BUILDING);
            turn.setMessageCount(0);
            turn.setTokenEstimate(0);
            turn.setStartedAt(LocalDateTime.now());
            turnDao.insert(turn);
        }
        if (!Objects.equals(turn.getSessionId(), sessionId) || !Objects.equals(turn.getRunId(), runId)) {
            throw new IllegalStateException("working memory segment owner mismatch");
        }
        WorkingMemoryMessage row = projector.project(List.of(message), sessionId, segmentId, runId).get(0);
        row.setTurnId(turn.getId());
        row.setSeqNo(seqNo);
        if (message.getMetadata() == null || !message.getMetadata().containsKey("originMessageKey")) {
            row.setOriginMessageKey(segmentId + ":" + seqNo);
        }
        WorkingMemoryMessage existing = messageDao.selectByTurnAndSeq(turn.getId(), seqNo);
        if (existing != null) {
            if (!sameMessage(existing, row)) throw new IllegalStateException("working memory message conflict");
            return;
        }
        if (turn.getStatus() != WorkingMemoryTurn.BUILDING || turn.getMessageCount() != seqNo) {
            throw new IllegalStateException("working memory segment is not appendable");
        }
        row.setTokenEstimate(estimate(message));
        messageDao.insert(row);
        if (turnDao.advanceBuilding(turn.getId(), seqNo, row.getTokenEstimate()) != 1) {
            throw new IllegalStateException("working memory segment changed during append");
        }
    }

    @Override
    @Transactional
    public void completeSegment(String segmentId) {
        if (segmentId == null) return;
        WorkingMemoryTurn turn = turnDao.selectByRequestId(segmentId);
        if (turn == null) throw new IllegalStateException("working memory segment not found");
        if (turn.getStatus() == WorkingMemoryTurn.READY) return;
        if (turn.getStatus() != WorkingMemoryTurn.BUILDING || turnDao.completeBuilding(segmentId) != 1) {
            throw new IllegalStateException("working memory segment cannot complete");
        }
    }

    /** 正常轮次只追加新增消息；requestId 已存在时返回 false。 */
    @Override
    @Transactional
    public boolean persistTurn(String sessionId, String requestId, String runId, List<LlmMessage> messages) {
        if (invalid(sessionId, requestId, messages) || turnDao.selectByRequestId(requestId) != null) return false;
        insertTurn(sessionId, requestId, runId, "react", messages);
        return true;
    }

    /** 同一事务内先失效旧 READY，再写入完整压缩投影及其消息。 */
    @Override
    @Transactional
    public boolean replaceReadyProjection(String sessionId, String compactRequestId, List<LlmMessage> messages) {
        if (invalid(sessionId, compactRequestId, messages) || turnDao.selectByRequestId(compactRequestId) != null) return false;
        List<Long> ids = turnDao.selectLoadable(sessionId).stream().map(WorkingMemoryTurn::getId).toList();
        if (!ids.isEmpty() && turnDao.invalidateLoadable(sessionId, ids) != ids.size()) {
            throw new IllegalStateException("working memory projection changed during compaction");
        }
        insertTurn(sessionId, compactRequestId, null, "compaction", messages);
        return true;
    }

    @Override
    public void recordCompaction(WorkingMemoryCompactionEvent event) {
        compactionDao.insert(event);
    }

    /** 统一写入轮次元数据与有序消息；由调用方的事务保证整体提交。 */
    private void insertTurn(String sessionId, String requestId, String runId, String entryAgent,
                            List<LlmMessage> messages) {
        List<WorkingMemoryMessage> rows = projector.project(messages, sessionId, requestId, runId);
        if (rows.isEmpty()) return;
        WorkingMemoryTurn turn = new WorkingMemoryTurn();
        turn.setSessionId(sessionId);
        turn.setRequestId(requestId);
        turn.setRunId(runId);
        turn.setTurnSeq(nextSeq(sessionId));
        turn.setEntryAgent(entryAgent);
        turn.setStatus(WorkingMemoryTurn.READY);
        turn.setMessageCount(rows.size());
        turn.setTokenEstimate(messages.stream().mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length() / 4 + 1).sum());
        turn.setStartedAt(LocalDateTime.now());
        turn.setFinishedAt(turn.getStartedAt());
        turnDao.insert(turn);
        for (WorkingMemoryMessage row : rows) {
            row.setTurnId(turn.getId());
            messageDao.insert(row);
        }
    }

    private boolean invalid(String sessionId, String requestId, List<LlmMessage> messages) {
        return sessionId == null || sessionId.isBlank() || requestId == null || requestId.isBlank()
                || messages == null || messages.isEmpty();
    }

    private int nextSeq(String sessionId) {
        Integer max = turnDao.selectMaxSeq(sessionId);
        return (max == null ? 0 : max) + 1;
    }

    private int estimate(LlmMessage message) {
        return message.getContent() == null ? 0 : message.getContent().length() / 4 + 1;
    }

    private boolean sameMessage(WorkingMemoryMessage first, WorkingMemoryMessage second) {
        return Objects.equals(first.getRole(), second.getRole())
                && Objects.equals(first.getContent(), second.getContent())
                && Objects.equals(first.getToolCallId(), second.getToolCallId())
                && Objects.equals(first.getToolCallsJson(), second.getToolCallsJson())
                && Objects.equals(first.getOriginMessageKey(), second.getOriginMessageKey());
    }
}
