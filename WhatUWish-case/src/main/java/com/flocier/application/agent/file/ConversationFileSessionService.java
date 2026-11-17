package com.flocier.application.agent.file;

import com.flocier.domain.agent.runtime.file.ConversationFileRepository;
import com.flocier.domain.agent.runtime.file.ConversationFileSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Run 前文件会话的身份记录；当前本地单用户阶段不签发浏览器访问令牌。
 */
@Service
public class ConversationFileSessionService {
    private final ConversationFileRepository repository;
    private final Duration lifetime;

    public ConversationFileSessionService(ConversationFileRepository repository,
                                          @Value("${conversation.files.session-lifetime-days:30}") long lifetimeDays) {
        if (lifetimeDays < 1 || lifetimeDays > 365) throw new IllegalArgumentException("文件会话有效期无效");
        this.repository = repository;
        this.lifetime = Duration.ofDays(lifetimeDays);
    }

    /** 签发独立于 Run 的会话 ID，使用户可以先上传、后发送任务。 */
    public IssuedSession create() {
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(lifetime);
        // 旧表仍要求 token_hash 非空；字段仅保留兼容，不再参与访问判断。
        repository.createSession(new ConversationFileSession(sessionId, "0".repeat(64), now, expiresAt));
        return new IssuedSession(sessionId);
    }

    /** 本地单用户阶段只确认文件会话存在；未来用户身份应在统一入口校验归属。 */
    public void requireAccess(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ConversationFileAccessException("FILE_SESSION_FORBIDDEN");
        }
        if (repository.findSession(sessionId).isEmpty()) {
            throw new ConversationFileAccessException("FILE_SESSION_FORBIDDEN");
        }
    }

    /** 新文件会话须存在；没有文件会话记录的旧纯文本 Run 仍可发送。 */
    public void requireForRun(String sessionId, boolean hasFiles) {
        if (hasFiles || repository.findSession(sessionId).isPresent()) requireAccess(sessionId);
    }

    public record IssuedSession(String sessionId) {
    }
}
