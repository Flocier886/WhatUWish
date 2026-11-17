package com.flocier.domain.ledger.model.entity;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 图片生成专属当前状态与终态投影；图片文件仍由 Artifact Ledger 和 Storage 作为权威。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationExecutionEntity {
    private String imageGenerationExecutionId;
    private String executionSource;
    private String toolInvocationId;
    private String pythonExecutionId;
    private String runId;
    private String sessionId;
    private String requestId;
    private String generationMode;
    private String requestedSize;
    private Integer requestedOutputCount;
    private Integer generatedOutputCount;
    private Integer referenceArtifactCount;
    private Integer maskArtifactCount;
    private Boolean usedFallback;
    private String status;
    private String finalSummary;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private Long versionNo;
    private Instant createdAt;
    private Instant updatedAt;
}
