package com.flocier.domain.agent.service.execute.react.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.summary.SummaryAgent;
import com.flocier.domain.agent.service.execute.react.step.factory.DefaultReactWorkflowStrategyFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.ArrayList;

@Service
public class ReactWorkflowRunAgentsNode extends AbstractReactWorkflowNode {

    @Resource
    private ReActAgent reActAgent;
    @Resource
    private SummaryAgent summaryAgent;
    @Resource
    private ReactWorkflowFinishNode reactWorkflowFinishNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultReactWorkflowStrategyFactory.DynamicContext dynamicContext) throws Exception {
        AgentContext agentContext = agentContext(dynamicContext);
        String reactResult = reActAgent.run(agentContext);
        agentContext.setResult(reactResult);
        // SummaryAgent 会继续写消息；此处先冻结只属于主 ReAct 的轨迹。
        agentContext.setReactMemorySnapshot(new ArrayList<>(agentContext.getMemory()));
        String summaryResult = summaryAgent.run(agentContext);
        agentContext.setResult(summaryResult);
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultReactWorkflowStrategyFactory.DynamicContext dynamicContext) {
        return reactWorkflowFinishNode;
    }
}
