package com.flocier.infrastructure.adapter.repository;

import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import com.flocier.domain.agent.runtime.file.ConversationFileRepository;
import com.flocier.domain.agent.runtime.file.ConversationFileSession;
import com.flocier.infrastructure.dao.IConversationFileDao;
import com.flocier.infrastructure.dao.po.ConversationFilePO;
import com.flocier.infrastructure.dao.po.ConversationFileSessionPO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** MySQL 会话文件仓储适配器：将领域文件状态映射为独立的会话和文件元数据表。 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "mysql")
public class MysqlConversationFileRepository implements ConversationFileRepository {
    private final IConversationFileDao dao;

    @Override
    public void createSession(ConversationFileSession session) {
        ConversationFileSessionPO po = new ConversationFileSessionPO();
        po.setSessionId(session.sessionId());
        po.setTokenHash(session.tokenHash());
        po.setCreatedAt(toLocal(session.createdAt()));
        po.setExpiresAt(toLocal(session.expiresAt()));
        if (dao.insertSession(po) != 1) throw new IllegalStateException("文件会话创建失败");
    }

    @Override
    public Optional<ConversationFileSession> findSession(String sessionId) {
        ConversationFileSessionPO po = dao.findSession(sessionId);
        return po == null ? Optional.empty()
                : Optional.of(new ConversationFileSession(po.getSessionId(), po.getTokenHash(),
                toInstant(po.getCreatedAt()), toInstant(po.getExpiresAt())));
    }

    /** 通过条件 UPDATE 原子占用名额，避免并发上传超过上限。 */
    @Override
    public boolean reserveFileSlot(String sessionId, int limit) {
        return dao.reserveFileSlot(sessionId, limit) == 1;
    }

    @Override
    public void releaseFileSlot(String sessionId) {
        if (dao.releaseFileSlot(sessionId) != 1) throw new IllegalStateException("会话文件名额释放失败");
    }

    @Override
    public void save(ConversationFileRecord file) {
        ConversationFilePO po = new ConversationFilePO();
        po.setFileId(file.fileId());
        po.setSessionId(file.sessionId());
        po.setDisplayName(file.displayName());
        po.setMimeType(file.mimeType());
        po.setFileSize(file.fileSize());
        po.setFileHash(file.fileHash());
        po.setStorageType(file.storageType());
        po.setStorageKey(file.storageKey());
        po.setStatus(file.status());
        po.setCreatedAt(toLocal(file.createdAt()));
        po.setRemovedAt(toLocal(file.removedAt()));
        if (dao.insertFile(po) != 1) throw new IllegalStateException("会话文件登记失败");
    }

    @Override
    public Optional<ConversationFileRecord> findFile(String fileId) {
        return Optional.ofNullable(dao.findFile(fileId)).map(this::fromPo);
    }

    @Override
    public List<ConversationFileRecord> listFiles(String sessionId) {
        return dao.listFiles(sessionId).stream().map(this::fromPo).toList();
    }

    /** 状态更新使用预期旧状态作为条件，防止并发覆盖。 */
    @Override
    public boolean changeStatus(String fileId, String expectedStatus, String status, Instant removedAt) {
        return dao.changeStatus(fileId, expectedStatus, status, toLocal(removedAt)) == 1;
    }

    private ConversationFileRecord fromPo(ConversationFilePO po) {
        return new ConversationFileRecord(po.getFileId(), po.getSessionId(), po.getDisplayName(),
                po.getMimeType(), po.getFileSize(), po.getFileHash(), po.getStorageType(),
                po.getStorageKey(), po.getStatus(), toInstant(po.getCreatedAt()), toInstant(po.getRemovedAt()));
    }

    private LocalDateTime toLocal(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
