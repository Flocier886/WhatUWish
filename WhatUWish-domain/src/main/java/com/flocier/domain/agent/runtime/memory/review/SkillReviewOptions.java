package com.flocier.domain.agent.runtime.memory.review;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 对齐 master 生产复盘参数；模型和 Skill 库沿用主 ReAct 配置。 */
@Data
@Component
public class SkillReviewOptions {
    /** 总开关；关闭后不计数、不调度、不落复盘记录。 */
    @Value("${memory.skill-review.enabled:true}")
    private boolean enabled = true;
    /** 同会话有效成功轮数达到此间隔的倍数时尝试复盘。 */
    @Value("${memory.skill-review.nudge-interval:5}")
    private int nudgeInterval = 5;
    /** 后台复用 ReAct 时的最大决策步数。 */
    @Value("${memory.skill-review.max-steps:6}")
    private int maxSteps = 6;
    /** 等待 fork 完成的预算；超时后禁止新的文件写入。 */
    @Value("${memory.skill-review.timeout-seconds:90}")
    private int timeoutSeconds = 90;

    /** 启动时拒绝无效预算，避免取模异常或无界执行。 */
    @PostConstruct
    public void validate() {
        if (nudgeInterval < 1 || maxSteps < 1 || timeoutSeconds < 1)
            throw new IllegalArgumentException("Skill 复盘间隔、步数及超时必须大于零");
    }
}
