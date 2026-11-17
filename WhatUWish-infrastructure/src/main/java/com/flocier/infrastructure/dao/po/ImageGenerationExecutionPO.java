package com.flocier.infrastructure.dao.po;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationExecutionPO {
    private Long id;
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
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long versionNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
