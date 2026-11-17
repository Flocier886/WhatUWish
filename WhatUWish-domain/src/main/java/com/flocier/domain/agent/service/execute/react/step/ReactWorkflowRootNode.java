package com.flocier.domain.agent.service.execute.react.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.service.execute.react.step.factory.DefaultReactWorkflowStrategyFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
public class ReactWorkflowRootNode extends AbstractReactWorkflowNode {

    @Resource
    private ReactWorkflowRunAgentsNode reactWorkflowRunAgentsNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultReactWorkflowStrategyFactory.DynamicContext dynamicContext) throws Exception {
        AgentContext agentContext = agentContext(dynamicContext);
        if (agentContext.getPrinter() != null) {
            agentContext.getPrinter().send("status", "react workflow started");
        }
        if (agentContext.getRequestId() == null || agentContext.getRequestId().isBlank()) {
            agentContext.setRequestId(UUID.randomUUID().toString());
        }
        agentContext.setSessionId(requestParameter.getSessionId());
        if (agentContext.getTraceId() == null || agentContext.getTraceId().isBlank()) {
            agentContext.setTraceId(agentContext.getRequestId());
        }
        agentContext.setQuery(requestParameter.getMessage());
        if (!StringUtils.hasText(agentContext.getTask())) {
            agentContext.setTask(requestParameter.getMessage());
        }
        agentContext.setMaxStep(requestParameter.getMaxStep());
        if (agentContext.getCurrentStep() == null) {
            agentContext.setCurrentStep(1);
        }
        if (agentContext.getCompleted() == null) {
            agentContext.setCompleted(Boolean.FALSE);
        }
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultReactWorkflowStrategyFactory.DynamicContext dynamicContext) {
        return reactWorkflowRunAgentsNode;
    }
}
