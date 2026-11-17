package com.flocier.domain.agent.runtime.agent.tool;

import com.flocier.domain.agent.runtime.agent.AgentContext;

public interface ToolCollectionFactory {

    ToolCollection create(AgentContext context);
}
