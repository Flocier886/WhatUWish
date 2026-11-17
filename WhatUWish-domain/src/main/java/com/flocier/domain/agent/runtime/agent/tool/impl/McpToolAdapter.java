package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.McpToolAccessor;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.mcp.McpToolDefinition;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class McpToolAdapter implements BaseTool {

    private final McpToolDefinition definition;
    private final McpToolAccessor mcpToolAccessor;

    @Override
    public String name() {
        return definition.getExposedToolName();
    }

    @Override
    public String description() {
        return definition.getDescription();
    }

    @Override
    public Object params() {
        return definition.getInputSchema();
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request)) {
            return ToolResultPayload.failure(
                    "invalid_mcp_tool_input",
                    "McpToolAdapter only accepts ToolInvokeRequest input",
                    "MCP 工具调用入参无效，无法执行远端工具。"
            );
        }
        return mcpToolAccessor.execute(definition, request, context);
    }
}
