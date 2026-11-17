package com.flocier.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tool 调用账本表 PO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationLedgerPO {
    private Long id;
    private String toolInvocationId;
    private String toolCallId;
    private String llmInvocationId;
    private Integer dispatchOrder;
    private String runId;
    private String sessionId;
    private String requestId;
    private String traceId;
    private Integer stepNo;
    private String toolName;
    private String toolType;
    private String toolVersion;
    private String requestSummary;
    private String responseSummary;
    private String requestPayloadJson;
    private String responsePayloadJson;
    private Long latencyMs;
    private String status;
    private String errorCode;
    private String errorMessage;
    private String artifactRef;
    private String idempotencyKey;
    private String extJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
