package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** DeepSearch 专属当前状态与终态投影；阶段事实以运行事件账本为准。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeepSearchExecutionEntity {
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
    private Instant startedAt;
    private Instant finishedAt;
    private Long versionNo;
    private Instant createdAt;
    private Instant updatedAt;
}
