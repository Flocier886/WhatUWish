package com.flocier.domain.agent.runtime.warmup;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.entity.ArmoryCommandEntity;
import com.flocier.domain.agent.model.vo.AiAgentClientFlowConfigVO;
import com.flocier.domain.agent.model.vo.AiAgentEnumVO;
import com.flocier.domain.agent.model.vo.AiAgentVO;
import com.flocier.domain.agent.service.armory.node.factory.DefaultWarmupStrategyFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 预热域服务。
 * 当前实现复用了原有 armory 节点链作为 warmup 内核，
 * 但对外入口与后续扩展都应统一以 runtime.warmup 为准。
 */
@Service
public class WarmupService implements IWarmupService {

    @Resource
    private IAgentRepository repository;
    @Resource
    private DefaultWarmupStrategyFactory defaultWarmupStrategyFactory;

    @Override
    public List<AiAgentVO> warmupAllAvailableAgents() {
        List<AiAgentVO> aiAgentVOS = repository.queryAvailableAgents();
        for (AiAgentVO aiAgentVO : aiAgentVOS) {
            warmupAgent(aiAgentVO.getAgentId());
        }
        return aiAgentVOS;
    }

    @Override
    public void warmupAgent(String agentId) {
        List<AiAgentClientFlowConfigVO> clientFlowConfigs = repository.queryAiAgentClientsByAgentId(agentId);
        if (clientFlowConfigs == null || clientFlowConfigs.isEmpty()) {
            return;
        }

        List<String> commandIdList = clientFlowConfigs.stream()
                .map(AiAgentClientFlowConfigVO::getClientId)
                .collect(Collectors.toList());

        try {
            StrategyHandler<ArmoryCommandEntity, DefaultWarmupStrategyFactory.DynamicContext, String> warmupHandler =
                    defaultWarmupStrategyFactory.warmupStrategyHandler();
            warmupHandler.apply(
                    ArmoryCommandEntity.builder()
                            .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                            .commandIdList(commandIdList)
                            .build(),
                    new DefaultWarmupStrategyFactory.DynamicContext()
            );
        } catch (Exception e) {
            throw new RuntimeException("warmup agent failed", e);
        }
    }

    @Override
    public List<AiAgentVO> queryAvailableAgents() {
        return repository.queryAvailableAgents();
    }
}
