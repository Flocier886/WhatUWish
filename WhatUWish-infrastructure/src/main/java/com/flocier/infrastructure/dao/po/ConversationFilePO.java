package com.flocier.infrastructure.dao.po;

import lombok.Data;
import java.time.LocalDateTime;

/** 会话文件表映射；storageType/storageKey 仅供服务端定位内容，不进入前端 DTO。 */
@Data
public class ConversationFilePO {
    private String fileId;
    private String sessionId;
    private String displayName;
    private String mimeType;
    private Long fileSize;
    private String fileHash;
    private String storageType;
    private String storageKey;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime removedAt;
}
