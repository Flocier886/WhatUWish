package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import com.flocier.domain.agent.runtime.file.ConversationFileRepository;
import com.flocier.domain.agent.runtime.file.ConversationFileSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存模式下的会话文件仓储；与 MySQL 适配器保持相同的名额和状态语义。 */
@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryConversationFileRepository implements ConversationFileRepository {
    private final ConcurrentHashMap<String, ConversationFileSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConversationFileRecord> files = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> fileSlots = new ConcurrentHashMap<>();

    @Override
    public void createSession(ConversationFileSession session) {
        if (sessions.putIfAbsent(session.sessionId(), session) != null) {
            throw new IllegalStateException("文件会话已存在");
        }
    }

    @Override
    public Optional<ConversationFileSession> findSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public boolean reserveFileSlot(String sessionId, int limit) {
        if (!sessions.containsKey(sessionId)) return false;
        boolean[] reserved = {false};
        fileSlots.compute(sessionId, (id, current) -> {
            int count = current == null ? 0 : current;
            if (count >= limit) return count;
            reserved[0] = true;
            return count + 1;
        });
        return reserved[0];
    }

    @Override
    public void releaseFileSlot(String sessionId) {
        fileSlots.compute(sessionId, (id, current) -> {
            if (current == null || current <= 0) throw new IllegalStateException("会话文件名额释放失败");
            return current - 1;
        });
    }

    @Override
    public void save(ConversationFileRecord file) {
        if (!sessions.containsKey(file.sessionId()) || files.putIfAbsent(file.fileId(), file) != null) {
            throw new IllegalStateException("会话文件登记失败");
        }
    }

    @Override
    public Optional<ConversationFileRecord> findFile(String fileId) {
        return Optional.ofNullable(files.get(fileId));
    }

    @Override
    public List<ConversationFileRecord> listFiles(String sessionId) {
        return files.values().stream().filter(file -> sessionId.equals(file.sessionId()))
                .sorted(Comparator.comparing(ConversationFileRecord::createdAt)
                        .thenComparing(ConversationFileRecord::fileId)).toList();
    }

    @Override
    public boolean changeStatus(String fileId, String expectedStatus, String status, Instant removedAt) {
        boolean[] changed = {false};
        files.computeIfPresent(fileId, (id, file) -> {
            if (!expectedStatus.equals(file.status())) return file;
            changed[0] = true;
            return new ConversationFileRecord(file.fileId(), file.sessionId(), file.displayName(), file.mimeType(),
                    file.fileSize(), file.fileHash(), file.storageType(), file.storageKey(), status, file.createdAt(), removedAt);
        });
        return changed[0];
    }
}
