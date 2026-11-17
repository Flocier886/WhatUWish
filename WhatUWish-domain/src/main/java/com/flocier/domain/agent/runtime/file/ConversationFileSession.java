package com.flocier.domain.agent.runtime.file;

import java.time.Instant;

/** Run 前创建的文件会话授权记录：只持久化令牌摘要，不保存明文令牌。 */
public record ConversationFileSession(String sessionId, String tokenHash, Instant createdAt, Instant expiresAt) {
}
