package com.flocier.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Session 账本表 PO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionLedgerPO {
    private Long id;
    private String sessionId;
    private String tenantId;
    private String userId;
    private String agentId;
    private String title;
    private String status;
    private String latestRequestId;
    private String latestQueryText;
    private String latestSummaryText;
    private Integer runCount;
    private Integer finishedRunCount;
    private Integer failedRunCount;
    private String idempotencyKey;
    private String extJson;
    private LocalDateTime startedAt;
    private LocalDateTime lastActiveAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
