package com.flocier.domain.agent.service.execute.react.step.factory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.service.execute.react.step.ReactWorkflowRootNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

@Service("reactWorkflowStrategyFactory")
public class DefaultReactWorkflowStrategyFactory {

    private final ReactWorkflowRootNode reactWorkflowRootNode;

    public DefaultReactWorkflowStrategyFactory(ReactWorkflowRootNode reactWorkflowRootNode) {
        this.reactWorkflowRootNode = reactWorkflowRootNode;
    }

    public StrategyHandler<ExecuteCommandEntity, DynamicContext, String> workflowStrategyHandler() {
        return reactWorkflowRootNode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DynamicContext {
        private AgentContext agentContext;
    }
}
