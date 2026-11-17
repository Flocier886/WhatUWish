package com.flocier.domain.agent.runtime.agent.tool;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.mcp.McpToolDefinition;

import java.util.List;

public interface McpToolAccessor {

    List<McpToolDefinition> listTools();

    ToolResultPayload execute(McpToolDefinition definition, ToolInvokeRequest request, AgentContext context);
}
