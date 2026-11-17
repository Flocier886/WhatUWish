package com.flocier.domain.agent.runtime.agent.execute;

import com.flocier.domain.agent.runtime.agent.AbstractThinkActAgent;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.plan.ReactThinkResult;

public class ExecuteAgent extends AbstractThinkActAgent {

    @Override
    protected ReactThinkResult think(AgentContext context) {
        throw new UnsupportedOperationException("ExecuteAgent is not implemented in this phase");
    }

    @Override
    protected void act(AgentContext context, ReactThinkResult thinkResult) {
        throw new UnsupportedOperationException("ExecuteAgent is not implemented in this phase");
    }
}
