package com.flocier.domain.agent.runtime.file;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 会话文件元数据 Port；上传事实与每轮 Run 的 input Artifact 分开保存。 */
public interface ConversationFileRepository {
    void createSession(ConversationFileSession session);
    Optional<ConversationFileSession> findSession(String sessionId);
    /** 原子预留文件名额，避免并发上传先查询后插入时突破会话上限。 */
    boolean reserveFileSlot(String sessionId, int limit);
    /** 上传失败或软移除后释放已预留的名额。 */
    void releaseFileSlot(String sessionId);
    void save(ConversationFileRecord file);
    Optional<ConversationFileRecord> findFile(String fileId);
    List<ConversationFileRecord> listFiles(String sessionId);
    /** 按旧状态条件更新，避免并发移除或降级覆盖较新的文件状态。 */
    boolean changeStatus(String fileId, String expectedStatus, String status, Instant removedAt);
}
