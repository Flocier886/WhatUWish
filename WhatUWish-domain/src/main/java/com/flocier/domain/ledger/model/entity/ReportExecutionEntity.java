package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Report 专属当前状态与终态投影；正文由正式 Artifact 保存，阶段事实以运行事件账本为准。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExecutionEntity {
    private String reportExecutionId;
    private String toolInvocationId;
    private String pythonExecutionId;
    private String runId;
    private String sessionId;
    private String requestId;
    private String taskSummary;
    private String outputType;
    private String outputFileName;
    private String status;
    private Integer inputArtifactCount;
    private String outputArtifactId;
    private String finalSummary;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private Long versionNo;
    private Instant createdAt;
    private Instant updatedAt;
}
