package com.flocier.infrastructure.adapter.repository;

import com.flocier.domain.agent.runtime.memory.review.*;
import com.flocier.infrastructure.dao.ISkillReviewExecutionDao;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** MySQL 复盘审计适配器；先建立记录才允许 fork 修改文件。 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "mysql")
public class MysqlSkillReviewExecutionRepository implements SkillReviewExecutionRepository {
    private final ISkillReviewExecutionDao dao;
    public void insert(SkillReviewExecution execution) { dao.insert(execution); }
    public void finish(SkillReviewExecution execution) {
        if (dao.finish(execution) != 1) throw new IllegalStateException("复盘记录不处于 RUNNING，拒绝覆盖终态");
    }
}
