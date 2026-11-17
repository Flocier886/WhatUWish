package com.flocier.infrastructure.dao;

import com.flocier.domain.agent.runtime.memory.review.SkillReviewExecution;
import org.apache.ibatis.annotations.Mapper;

/** Skill 复盘独立记录表访问，不写主运行账本。 */
@Mapper
public interface ISkillReviewExecutionDao {
    void insert(SkillReviewExecution execution);
    /** 仅更新 RUNNING 行，防止已结束复盘被晚到结果覆盖。 */
    int finish(SkillReviewExecution execution);
}
