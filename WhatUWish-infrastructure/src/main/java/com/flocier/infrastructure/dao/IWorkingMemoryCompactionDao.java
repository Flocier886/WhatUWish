package com.flocier.infrastructure.dao;

import com.flocier.domain.agent.runtime.memory.WorkingMemoryCompactionEvent;
import org.apache.ibatis.annotations.Mapper;

/** 工作记忆压缩审计表的 MyBatis 写入入口。 */
@Mapper
public interface IWorkingMemoryCompactionDao {
    void insert(WorkingMemoryCompactionEvent event);
}
