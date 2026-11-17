package com.flocier.domain.agent.adapter.repository;

import com.flocier.domain.agent.model.entity.ShortTermMemoryAppendCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryResultEntity;
import com.flocier.domain.agent.model.vo.*;

import java.util.List;
import java.util.Map;

public interface IAgentRepository {
    List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList);

    List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList);

    List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList);

    /** 新 ReAct Runtime 直接使用全部启用的 MCP，不引入 client 绑定。 */
    List<AiClientToolMcpVO> queryEnabledAiClientToolMcpVOList();

    List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList);

    Map<String,AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList);

    List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList);

    List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList);

    List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList);

    List<AiClientModelVO> queryAiClientModelVOListByModelIds(List<String> modelIdList);

    Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId);

    AiAgentVO queryAiAgentByAgentId(String aiAgentId);

    List<AiAgentClientFlowConfigVO> queryAiClientsByAgentId(String aiAgentId);

    List<AiAgentVO> queryAvailableAgents();

    List<AiAgentClientFlowConfigVO> queryAiAgentClientsByAgentId(String agentId);

    void createTagOrder(AiRagOrderVO aiRagOrderVO);

    void appendShortTermMemory(ShortTermMemoryAppendCommandEntity commandEntity);

    ShortTermMemoryQueryResultEntity queryShortTermMemory(ShortTermMemoryQueryCommandEntity commandEntity);
}
