package com.flocier.domain.agent.runtime.agent.tool;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.mcp.McpRuntimeRegistry;
import com.flocier.domain.agent.runtime.agent.tool.mcp.McpToolDefinition;
import com.flocier.domain.agent.runtime.agent.tool.mcp.McpToolResultMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultMcpToolAccessor implements McpToolAccessor {

    private final McpRuntimeRegistry registry;
    private final McpToolResultMapper resultMapper;

    @Override
    public List<McpToolDefinition> listTools() {
        return registry.listTools();
    }

    @Override
    public ToolResultPayload execute(McpToolDefinition definition, ToolInvokeRequest request, AgentContext context) {
        try {
            McpSchema.CallToolResult result = registry.callTool(definition, request.getArguments());
            return resultMapper.map(definition, result);
        } catch (RuntimeException exception) {
            return resultMapper.mapException(definition, exception);
        }
    }
}
