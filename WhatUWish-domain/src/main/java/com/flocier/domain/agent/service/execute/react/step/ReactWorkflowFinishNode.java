package com.flocier.domain.agent.service.execute.react.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.summary.SummaryAgent;
import com.flocier.domain.agent.service.execute.react.step.factory.DefaultReactWorkflowStrategyFactory;
import org.springframework.stereotype.Service;

@Service
public class ReactWorkflowFinishNode extends AbstractReactWorkflowNode {

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultReactWorkflowStrategyFactory.DynamicContext dynamicContext) {
        AgentContext agentContext = agentContext(dynamicContext);
        String finalSummary = agentContext.getAttribute(SummaryAgent.FINAL_SUMMARY_KEY);
        if (finalSummary == null) {
            finalSummary = agentContext.getResult();
        }
        return finalSummary;
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultReactWorkflowStrategyFactory.DynamicContext dynamicContext) {
        return defaultStrategyHandler;
    }
}
