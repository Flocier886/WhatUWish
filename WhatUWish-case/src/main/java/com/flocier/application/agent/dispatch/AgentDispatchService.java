package com.flocier.application.agent.dispatch;

import com.flocier.application.agent.execute.AgentExecuteCommand;
import com.flocier.application.agent.execute.IExecuteStrategy;
import com.flocier.application.agent.stream.AgentSessionStream;
import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.vo.AiAgentVO;
import com.flocier.types.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Service
public class AgentDispatchService implements IAgentDispatchService {

    private static final String WORKFLOW_STRATEGY = "workflowAgentExecuteStrategy";
    private static final String REACT_STRATEGY = "reactAgentExecuteStrategy";

    @Resource
    private Map<String, IExecuteStrategy> executeStrategyMap;
    @Resource
    private IAgentRepository repository;
    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Override
    public void dispatch(AgentExecuteCommand request, AgentSessionStream stream) throws Exception {
        AiAgentVO aiAgentVO = repository.queryAiAgentByAgentId(request.getAiAgentId());
        if (aiAgentVO == null) {
            throw new BizException("智能体不存在 agentId:" + request.getAiAgentId());
        }

        String strategy = resolveStrategy(aiAgentVO.getStrategy());
        IExecuteStrategy executeStrategy = executeStrategyMap.get(strategy);
        if (executeStrategy == null) {
            throw new BizException("不存在的执行策略 strategy:" + strategy);
        }
        request.setLegacyStrategy(aiAgentVO.getStrategy());

        threadPoolExecutor.execute(() -> {
            try {
                executeStrategy.execute(request, stream);
            } catch (Exception e) {
                log.error("workflow execute failed", e);
                stream.error("执行异常: " + e.getMessage(), e);
            } finally {
                stream.complete();
            }
        });
    }

    private String resolveStrategy(String legacyStrategy) {
        if ("reactAgentExecuteStrategy".equals(legacyStrategy)) {
            return REACT_STRATEGY;
        }
        if ("autoAgentExecuteStrategy".equals(legacyStrategy)
                || "flowAgentExecuteStrategy".equals(legacyStrategy)
                || "fixedAgentExecuteStrategy".equals(legacyStrategy)) {
            return WORKFLOW_STRATEGY;
        }
        return WORKFLOW_STRATEGY;
    }
}
