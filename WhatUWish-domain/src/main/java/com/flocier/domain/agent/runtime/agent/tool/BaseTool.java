package com.flocier.domain.agent.runtime.agent.tool;

import com.flocier.domain.agent.runtime.agent.AgentContext;

public interface BaseTool {

    String name();

    String description();

    Object params();

    Object execute(Object input, AgentContext context);
}
