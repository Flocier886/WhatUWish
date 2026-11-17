package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 LLM 调用的账本记录。
 * 用来承接模型、token、摘要、步骤和上下文关联信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocationLedgerEntity {

    /** LLM 调用记录唯一标识。 */
    private String llmInvocationId;
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
    /** 模型提供方。 */
    private String provider;
    /** 模型名称。 */
    private String model;
    /** 调用场景，例如 summary、react_tool_ask。 */
    private String scene;
    /** 预留的 prompt 模板标识。 */
    private String promptTemplateId;
    /** 输入摘要。 */
    private String requestSummary;
    private String requestPayloadJson;
    /** 输出摘要。 */
    private String responseSummary;
    private String responsePayloadJson;
    /** 输入 token 数。 */
    private Integer inputTokens;
    /** 输出 token 数。 */
    private Integer outputTokens;
    /** 总 token 数。 */
    private Integer totalTokens;
    /** 调用耗时，单位毫秒。 */
    private Long latencyMs;
    /** 当前调用状态。 */
    private String status;
    /** 调用开始时间。 */
    private Instant startedAt;
    /** 调用结束时间。 */
    private Instant finishedAt;
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
