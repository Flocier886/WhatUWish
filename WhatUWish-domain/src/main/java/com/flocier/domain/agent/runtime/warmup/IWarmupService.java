package com.flocier.domain.agent.runtime.warmup;

import com.flocier.domain.agent.model.vo.AiAgentVO;

import java.util.List;

public interface IWarmupService {

    List<AiAgentVO> warmupAllAvailableAgents();

    void warmupAgent(String agentId);

    List<AiAgentVO> queryAvailableAgents();
}
