package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次完整执行主链路的账本记录。
 * 用来串联同一次 run 下的 LLM、Tool 和 Artifact 明细。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunLedgerEntity {

    /** 本次执行的唯一标识。 */
    private String runId;
    /** 所属会话标识。 */
    private String sessionId;
    /** 外部请求标识。 */
    private String requestId;
    /** 链路追踪标识。 */
    private String traceId;
    /** 当前执行的 Agent 标识。 */
    private String agentId;
    /** 预留的租户标识。 */
    private String tenantId;
    /** 预留的用户标识。 */
    private String userId;
    /** 触发来源，例如 react、workflow、llm。 */
    private String triggerSource;
    /** 执行状态，例如 RUNNING、COMPLETED、FAILED。 */
    private String status;
    /** 执行开始时间。 */
    private Instant startedAt;
    /** 执行结束时间。 */
    private Instant finishedAt;
    /** 错误码。 */
    private String errorCode;
    /** 错误信息。 */
    private String errorMessage;
    /** 本次执行的结果摘要。 */
    private String summary;
    /** Original user input for this Run; a first-class ledger fact. */
    private String queryText;
    /** 幂等键，用于避免重复写入。 */
    private String idempotencyKey;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 预留扩展字段。 */
    @Builder.Default
    private Map<String, Object> ext = new LinkedHashMap<>();
}
