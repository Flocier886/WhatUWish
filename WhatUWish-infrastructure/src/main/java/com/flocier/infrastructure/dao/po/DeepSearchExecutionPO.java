package com.flocier.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeepSearchExecutionPO {
    private Long id;
    private String deepSearchExecutionId;
    private String toolInvocationId;
    private String pythonExecutionId;
    private String runId;
    private String sessionId;
    private String requestId;
    private String queryText;
    private String researchMode;
    private String status;
    private String finalSummary;
    private String reportArtifactId;
    private String errorCode;
    private String errorMessage;
    private Integer lastSequenceNo;
    private String lastUpstreamEventId;
    private String schemaVersion;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long versionNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
