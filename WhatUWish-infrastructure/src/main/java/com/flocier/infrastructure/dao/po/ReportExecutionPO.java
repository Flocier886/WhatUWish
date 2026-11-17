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
public class ReportExecutionPO {
    private Long id;
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
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long versionNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
