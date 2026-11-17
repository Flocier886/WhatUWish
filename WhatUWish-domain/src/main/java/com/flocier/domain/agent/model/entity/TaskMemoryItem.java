package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 任务推进记忆项。
 * 用于表达当前会话在持续任务上的阶段、结果与下一步。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskMemoryItem {

    /** 记忆项唯一标识。 */
    private String memoryId;
    /** 所属会话标识。 */
    private String sessionId;
    /** 来源 run 标识。 */
    private String runId;
    /** 任务类型，如 active_goal、completed_run、failed_run。 */
    private String taskType;
    /** 任务摘要。 */
    private String taskSummary;
    /** 当前推进状态。 */
    private String progressStatus;
    /** 推荐的下一步动作。 */
    private String nextAction;
    /** 当前阻塞点。 */
    private String blockingIssue;
    /** 记忆项创建时间。 */
    private Instant createdAt;
}
