package com.flocier.domain.agent.runtime.file;

import java.time.Instant;

/** 服务端保存的不可变文件身份及内容定位；status/removedAt 决定新 Run 能否再次选用。 */
public record ConversationFileRecord(
        String fileId, String sessionId, String displayName, String mimeType,
        long fileSize, String fileHash, String storageType, String storageKey,
        String status, Instant createdAt, Instant removedAt) {
    public boolean ready() {
        return "READY".equals(status) && removedAt == null;
    }
}
