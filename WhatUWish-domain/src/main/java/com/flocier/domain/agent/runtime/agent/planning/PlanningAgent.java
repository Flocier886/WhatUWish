package com.flocier.domain.agent.runtime.agent.planning;

import com.flocier.domain.agent.runtime.agent.AbstractThinkActAgent;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.plan.ReactThinkResult;

public class PlanningAgent extends AbstractThinkActAgent {

    @Override
    protected ReactThinkResult think(AgentContext context) {
        throw new UnsupportedOperationException("PlanningAgent is not implemented in this phase");
    }

    @Override
    protected void act(AgentContext context, ReactThinkResult thinkResult) {
        throw new UnsupportedOperationException("PlanningAgent is not implemented in this phase");
    }
}
