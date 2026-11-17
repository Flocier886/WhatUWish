package com.flocier.domain.agent.service;

import com.flocier.domain.agent.model.entity.ShortTermMemoryAppendCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryResultEntity;

public interface IMemoryService {
    void appendShortTermMemory(ShortTermMemoryAppendCommandEntity commandEntity);

    ShortTermMemoryQueryResultEntity queryShortTermMemory(ShortTermMemoryQueryCommandEntity commandEntity);
}
