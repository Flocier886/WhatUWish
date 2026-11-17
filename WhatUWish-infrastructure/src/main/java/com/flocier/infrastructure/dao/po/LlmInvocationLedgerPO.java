package com.flocier.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LLM 调用账本表 PO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocationLedgerPO {
    private Long id;
    private String llmInvocationId;
    private String runId;
    private String sessionId;
    private String requestId;
    private String traceId;
    private Integer stepNo;
    private String provider;
    private String model;
    private String scene;
    private String promptTemplateId;
    private String requestSummary;
    private String responseSummary;
    private String requestPayloadJson;
    private String responsePayloadJson;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long latencyMs;
    private String status;
    private String artifactRef;
    private String idempotencyKey;
    private String extJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
