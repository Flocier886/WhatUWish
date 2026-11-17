package com.flocier.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.model.entity.ArmoryCommandEntity;
import com.flocier.domain.agent.model.vo.AiAgentEnumVO;
import com.flocier.domain.agent.model.vo.AiClientSystemPromptVO;
import com.flocier.domain.agent.model.vo.AiClientVO;
import com.flocier.domain.agent.service.armory.node.factory.DefaultWarmupStrategyFactory;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport {
    @Override
    protected String doApply(ArmoryCommandEntity armoryCommandEntity, DefaultWarmupStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("AiClientNode start: {}", JSON.toJSONString(armoryCommandEntity));

        List<AiClientVO> aiClientVOS = dynamicContext.getValue(dataName());
        if (aiClientVOS == null || aiClientVOS.isEmpty()) {
            return router(armoryCommandEntity, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap =
                dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());

        for (AiClientVO aiClientVO : aiClientVOS) {
            StringBuilder defaultSystem = new StringBuilder("Ai 智能体\n");
            List<String> promptIdList = aiClientVO.getPromptIdList();
            for (String promptId : promptIdList) {
                AiClientSystemPromptVO systemPromptVO = systemPromptMap.get(promptId);
                if (systemPromptVO != null) {
                    defaultSystem.append(systemPromptVO.getPromptContent());
                }
            }

            OpenAiChatModel chatModel = getBean(aiClientVO.getModelBeanName());

            List<McpSyncClient> mcpSyncClients = new ArrayList<>();
            for (String mcpBeanName : aiClientVO.getMcpBeanNameList()) {
                mcpSyncClients.add(getBean(mcpBeanName));
            }

            List<Advisor> advisors = new ArrayList<>();
            for (String advisorBeanName : aiClientVO.getAdvisorBeanNameList()) {
                advisors.add(getBean(advisorBeanName));
            }

            ChatClient client = ChatClient.builder(chatModel)
                    .defaultSystem(defaultSystem.toString())
                    .defaultTemplateRenderer(new NoOpTemplateRenderer())
                    .defaultToolCallbacks(SyncMcpToolCallbackProvider.builder()
                            .mcpClients(mcpSyncClients.toArray(new McpSyncClient[]{}))
                            .build())
                    .defaultAdvisors(advisors.toArray(new Advisor[]{}))
                    .build();

            registerBean(beanName(aiClientVO.getClientId()), ChatClient.class, client);
            aiClientRuntimeRegistry.register(aiClientVO.getClientId(), client);
        }

        return router(armoryCommandEntity, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultWarmupStrategyFactory.DynamicContext, String> get(
            ArmoryCommandEntity armoryCommandEntity,
            DefaultWarmupStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    @Override
    protected String beanName(String id) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(id);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT.getDataName();
    }
}
