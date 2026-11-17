package com.flocier.domain.agent.service.execute.flow.step;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.client.AiClientRuntimeRegistry;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractExecuteSupport extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> {
    private final Logger log = LoggerFactory.getLogger(AbstractExecuteSupport.class);

    @Resource
    protected IAgentRepository repository;
    @Resource
    protected AiClientRuntimeRegistry aiClientRuntimeRegistry;
    @Resource
    protected LLM llm;

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
    }

    protected ChatClient getChatClientByClientId(String clientId) {
        return aiClientRuntimeRegistry.getRequiredChatClient(clientId);
    }

    protected AgentContext getAgentContext(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        return dynamicContext.getValue("agentContext");
    }

    protected void sendSseResult(DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                 AutoAgentExecuteResultEntity result) {
        AgentContext agentContext = getAgentContext(dynamicContext);
        if (agentContext != null && agentContext.getResultWriter() != null) {
            agentContext.getResultWriter().write(result);
            return;
        }
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                emitter.send("data: " + JSON.toJSONString(result) + "\n\n");
            }
        } catch (IOException e) {
            log.error("send result failed: {}", e.getMessage(), e);
        }
    }
}
