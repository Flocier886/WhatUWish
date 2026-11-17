package com.flocier.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 产物账本表 PO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactLedgerPO {
    private Long id;
    private String artifactId;
    private String runId;
    private String sessionId;
    private String requestId;
    private String traceId;
    private String toolInvocationId;
    private String toolCallId;
    private String artifactRole;
    private String visibility;
    private String sourceType;
    private String sourceId;
    private String sourceName;
    private String artifactType;
    private String artifactName;
    private String fileName;
    private String storageType;
    private String storageRef;
    private String storageKey;
    private String downloadUrl;
    private String previewUrl;
    private String contentSummary;
    private String mimeType;
    private Long size;
    private Long fileSize;
    private String fileHash;
    private String metadataJson;
    private Integer deleted;
    private LocalDateTime deletedAt;
    private String idempotencyKey;
    private String extJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
