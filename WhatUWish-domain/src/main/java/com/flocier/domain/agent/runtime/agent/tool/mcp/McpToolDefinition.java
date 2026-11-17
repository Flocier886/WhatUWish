package com.flocier.domain.agent.runtime.agent.tool.mcp;

import lombok.Builder;
import lombok.Value;

import java.util.LinkedHashMap;
import java.util.Map;

/** MCP tools/list 结果在新 ReAct Runtime 中的稳定工具定义。 */
@Value
@Builder
public class McpToolDefinition {
    String mcpId;
    String remoteToolName;
    String exposedToolName;
    String description;
    String transportType;
    @Builder.Default
    Map<String, Object> inputSchema = new LinkedHashMap<>();
}
