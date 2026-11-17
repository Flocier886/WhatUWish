package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 高价值事实记忆项。
 * 只保留后续执行仍值得复用的稳定结论或已确认事实。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactMemoryItem {

    /** 记忆项唯一标识。 */
    private String memoryId;
    /** 所属会话标识。 */
    private String sessionId;
    /** 事实来源类型，如 session、run、llm、tool。 */
    private String sourceType;
    /** 事实来源记录标识。 */
    private String sourceId;
    /** 事实类型，如 session_summary、run_summary、tool_observation。 */
    private String factType;
    /** 事实摘要。 */
    private String factSummary;
    /** 事实证据摘要。 */
    private String evidenceSummary;
    /** 置信度，范围 0-100。 */
    private Integer confidence;
    /** 是否属于用户已明确确认的事实。 */
    private boolean confirmedByUser;
    /** 记忆项创建时间。 */
    private Instant createdAt;
}
