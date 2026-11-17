package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 Tool 调用的账本记录。
 * 用来沉淀工具调用过程、结果状态、错误和上下文关联。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationLedgerEntity {

    /** Tool 调用记录唯一标识。 */
    private String toolInvocationId;
    /** 模型或运行时的工具调用 ID。 */
    private String toolCallId;
    /** 父 LLM 调用账本标识；非 LLM 触发或历史记录无法确认时允许为空。 */
    private String llmInvocationId;
    /** 同一父 LLM 返回多个工具调用时的稳定分发顺序，从 0 开始。 */
    private Integer dispatchOrder;
    /** 所属执行 run 标识。 */
    private String runId;
    /** 所属会话标识。 */
    private String sessionId;
    /** 所属请求标识。 */
    private String requestId;
    /** 所属链路追踪标识。 */
    private String traceId;
    /** 执行步骤序号。 */
    private Integer stepNo;
    /** 工具名称。 */
    private String toolName;
    /** 工具类型或来源类型。 */
    private String toolType;
    /** 预留的工具版本字段。 */
    private String toolVersion;
    /** 输入摘要。 */
    private String requestSummary;
    private String requestPayloadJson;
    /** 输出摘要。 */
    private String responseSummary;
    private String responsePayloadJson;
    /** 调用耗时，单位毫秒。 */
    private Long latencyMs;
    /** 当前调用状态。 */
    private String status;
    /** 调用开始时间。 */
    private Instant startedAt;
    /** 调用结束时间。 */
    private Instant finishedAt;
    /** 错误码。 */
    private String errorCode;
    /** 错误信息。 */
    private String errorMessage;
    /** 关联产物引用。 */
    private String artifactRef;
    /** 幂等键。 */
    private String idempotencyKey;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 预留扩展字段。 */
    @Builder.Default
    private Map<String, Object> ext = new LinkedHashMap<>();

    public boolean hasCompleteFactPayload() {
        return requestPayloadJson != null && !requestPayloadJson.isBlank()
                && responsePayloadJson != null && !responsePayloadJson.isBlank();
    }
}
