package com.flocier.domain.agent.runtime.agent.tool.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.locks.ReentrantLock;

/** 一个启用 MCP Server 对应的可复用同步客户端。 */
@Getter
@RequiredArgsConstructor
public class McpClientRuntime {
    private final String mcpId;
    private final String transportType;
    private final McpSyncClient client;
    private final ReentrantLock lock = new ReentrantLock();
}
