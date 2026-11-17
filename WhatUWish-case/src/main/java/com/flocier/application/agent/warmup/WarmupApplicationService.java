package com.flocier.application.agent.warmup;

import com.flocier.domain.agent.model.vo.AiAgentVO;
import com.flocier.domain.agent.runtime.warmup.IWarmupService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WarmupApplicationService implements IWarmupApplicationService {

    @Resource
    private IWarmupService warmupService;

    @Override
    public void warmupAgent(String agentId) {
        warmupService.warmupAgent(agentId);
    }

    @Override
    public List<AiAgentVO> queryAvailableAgents() {
        return warmupService.queryAvailableAgents();
    }
}
