package com.flocier.application.agent.warmup;

import com.flocier.domain.agent.model.vo.AiAgentVO;

import java.util.List;

public interface IWarmupApplicationService {

    void warmupAgent(String agentId);

    List<AiAgentVO> queryAvailableAgents();
}
