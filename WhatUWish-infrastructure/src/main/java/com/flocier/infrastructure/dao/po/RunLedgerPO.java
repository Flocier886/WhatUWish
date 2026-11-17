package com.flocier.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Run 账本表 PO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunLedgerPO {
    private Long id;
    private String runId;
    private String sessionId;
    private String requestId;
    private String traceId;
    private String agentId;
    private String tenantId;
    private String userId;
    private String triggerSource;
    private String status;
    private String errorCode;
    private String errorMessage;
    private String summary;
    private String queryText;
    private String idempotencyKey;
    private String extJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
