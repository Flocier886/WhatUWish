package com.flocier.domain.agent.runtime.memory.review;

/** 独立复盘记录端口，记录建立失败时禁止开始后台写文件。 */
public interface SkillReviewExecutionRepository {
    void insert(SkillReviewExecution execution);
    /** 只允许从 RUNNING 进入终态，防止晚返回覆盖 TIMEOUT。 */
    void finish(SkillReviewExecution execution);
}
