package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Session 级记忆视图。
 * 这是主链路注入账本记忆时的聚合结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMemoryView {

    /** 会话标识。 */
    private String sessionId;
    /** 当前 run 标识。 */
    private String currentRunId;
    /** 会话摘要。 */
    private String sessionSummary;
    /** 最近几次 run 的摘要。 */
    @Builder.Default
    private List<String> recentRunSummaries = new ArrayList<>();
    /** 已提取的重要事实。 */
    @Builder.Default
    private List<FactMemoryItem> factMemories = new ArrayList<>();
    /** 已提取的任务推进记忆。 */
    @Builder.Default
    private List<TaskMemoryItem> taskMemories = new ArrayList<>();
    /** 已提取的重要产物记忆。 */
    @Builder.Default
    private List<ArtifactMemoryItem> artifactMemories = new ArrayList<>();
    /** 按 Run/Cycle 组织的连续历史；这是当前 memory prompt 的唯一主体。 */
    private SessionHistoryMemory sessionHistory;
    /** 预算后保留的完整或语义降级 Run 历史。 */
    @Builder.Default
    private List<SessionHistoryMemory.RunHistory> retainedRunHistories = new ArrayList<>();
    /** 仅来自被预算淘汰 Run 的高价值 artifact/失败索引。 */
    @Builder.Default
    private List<SessionHistoryMemory.HistoricalIndexItem> historicalIndexes = new ArrayList<>();
    /** 最终可注入到 prompt 的记忆文本。 */
    private String injectionText;
    /** 预算估算值，当前按字符数近似。 */
    private Integer estimatedTokens;
}
