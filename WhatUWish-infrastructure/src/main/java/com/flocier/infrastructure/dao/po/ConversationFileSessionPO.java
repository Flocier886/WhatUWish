package com.flocier.infrastructure.dao.po;

import lombok.Data;
import java.time.LocalDateTime;

/** 文件会话授权表映射：tokenHash 是令牌摘要，active_file_count 由 SQL 原子更新。 */
@Data
public class ConversationFileSessionPO {
    private String sessionId;
    private String tokenHash;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
