package com.flocier.domain.agent.runtime.memory.review;

import lombok.Data;
import java.time.LocalDateTime;

/** 后台 Skill 复盘独立执行记录；正文留在 Skill 文件，不进入主 Ledger。 */
@Data
public class SkillReviewExecution {
    /** 数据库自增主键。 */
    private Long id;
    /** 单次复盘唯一标识。 */
    private String reviewId;
    /** 来源主会话。 */
    private String sessionId;
    /** 触发复盘的主请求。 */
    private String triggerRequestId;
    /** 触发复盘的主 Run。 */
    private String triggerRunId;
    /** 后台独立请求标识。 */
    private String forkRequestId;
    /** RUNNING/SUCCESS/FAILED/SKIPPED/TIMEOUT。 */
    private String status;
    /** 防重入或资源饱和等跳过原因。 */
    private String skipReason;
    /** 本进程内同会话有效成功轮数。 */
    private int successTurnCount;
    /** SummaryAgent 前冻结的消息数。 */
    private int snapshotMessageCount;
    /** 决策步数预算。 */
    private int maxSteps;
    /** 调度执行超时预算，单位秒。 */
    private int timeoutSeconds;
    /** 从执行开始计时的耗时，单位毫秒。 */
    private long durationMs;
    /** 实际变更的不同文件数，内容相同不计数。 */
    private int changedFileCount;
    /** 包名、包内路径和创建/修改类型，不包含正文及宿主路径。 */
    private String changedFilesJson;
    /** NOT_NEEDED/SUCCESS/INVALID/FAILED。 */
    private String refreshStatus;
    /** 变更包注册校验摘要。 */
    private String validationSummary;
    /** 受控错误分类，禁止保存模型完整响应。 */
    private String errorCode;
    /** 限长错误说明。 */
    private String errorMessage;
    /** 记录创建与最后更新时间。 */
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
