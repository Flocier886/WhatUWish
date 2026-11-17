package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session 账本实体。
 * Session 代表跨多次 run 的持续性会话维度，是后续 memory 与 replay 的事实头信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionLedgerEntity {

    /** 会话唯一标识。 */
    private String sessionId;
    /** 预留租户标识。 */
    private String tenantId;
    /** 预留用户标识。 */
    private String userId;
    /** 当前会话所属 Agent 标识。 */
    private String agentId;
    /** 会话标题，首期可为空。 */
    private String title;
    /** 会话状态。 */
    private String status;
    /** 最近一次请求标识。 */
    private String latestRequestId;
    /** 最近一次用户问题摘要。 */
    private String latestQueryText;
    /** 最近一次执行总结摘要。 */
    private String latestSummaryText;
    /** 该会话累计执行次数。 */
    private Integer runCount;
    /** 成功完成的 run 数量。 */
    private Integer finishedRunCount;
    /** 失败的 run 数量。 */
    private Integer failedRunCount;
    /** 会话首次开始时间。 */
    private Instant startedAt;
    /** 最近活跃时间。 */
    private Instant lastActiveAt;
    /** 幂等键。 */
    private String idempotencyKey;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 预留扩展字段。 */
    @Builder.Default
    private Map<String, Object> ext = new LinkedHashMap<>();
}
