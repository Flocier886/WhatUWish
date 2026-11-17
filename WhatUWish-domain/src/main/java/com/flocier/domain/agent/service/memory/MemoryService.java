package com.flocier.domain.agent.service.memory;

import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.entity.ShortTermMemoryAppendCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryResultEntity;
import com.flocier.domain.agent.service.IMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class MemoryService implements IMemoryService {
    @Resource
    private IAgentRepository repository;

    @Override
    public void appendShortTermMemory(ShortTermMemoryAppendCommandEntity commandEntity) {
        if (commandEntity == null || !StringUtils.hasText(commandEntity.getSessionId())) {
            throw new IllegalArgumentException("appendShortTermMemory 参数非法: sessionId 不能为空");
        }
        if (!StringUtils.hasText(commandEntity.getUserQuestion()) || !StringUtils.hasText(commandEntity.getFinalAnswer())) {
            throw new IllegalArgumentException("appendShortTermMemory 参数非法: userQuestion/finalAnswer 不能为空");
        }

        repository.appendShortTermMemory(commandEntity);
    }

    @Override
    public ShortTermMemoryQueryResultEntity queryShortTermMemory(ShortTermMemoryQueryCommandEntity commandEntity) {
        if (commandEntity == null || !StringUtils.hasText(commandEntity.getSessionId())) {
            throw new IllegalArgumentException("queryShortTermMemory 参数非法: sessionId 不能为空");
        }
        return repository.queryShortTermMemory(commandEntity);
    }
}
