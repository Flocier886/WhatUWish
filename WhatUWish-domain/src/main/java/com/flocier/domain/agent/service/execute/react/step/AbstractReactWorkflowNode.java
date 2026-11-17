package com.flocier.domain.agent.service.execute.react.step;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.service.execute.react.step.factory.DefaultReactWorkflowStrategyFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractReactWorkflowNode extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> {

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultReactWorkflowStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
    }

    protected AgentContext agentContext(DefaultReactWorkflowStrategyFactory.DynamicContext dynamicContext) {
        return dynamicContext.getAgentContext();
    }
}
