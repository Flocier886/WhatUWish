package com.flocier.domain.agent.runtime.agent.tool.mcp;

import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.vo.AiClientToolMcpVO;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 启用 MCP 的启动期快照、客户端生命周期和调用串行化入口。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpRuntimeRegistry {
    private static final int MAX_TOOL_NAME_LENGTH = 64;

    private final IAgentRepository repository;
    private final McpClientRuntimeFactory runtimeFactory;
    private final Map<String, McpClientRuntime> runtimes = new LinkedHashMap<>();
    private volatile List<McpToolDefinition> definitions = List.of();

    @PostConstruct
    public void initialize() {
        reload();
    }

    public synchronized void reload() {
        List<AiClientToolMcpVO> configs;
        try {
            configs = repository.queryEnabledAiClientToolMcpVOList();
        } catch (RuntimeException exception) {
            log.error("查询启用 MCP 配置失败，本次启动不加载 MCP: reason={}", exception.getMessage(), exception);
            return;
        }
        Map<String, McpClientRuntime> nextRuntimes = new LinkedHashMap<>();
        Map<String, McpToolDefinition> nextDefinitions = new LinkedHashMap<>();
        for (AiClientToolMcpVO config : configs == null ? List.<AiClientToolMcpVO>of() : configs) {
            McpClientRuntime runtime = null;
            try {
                runtime = runtimeFactory.create(config);
                Map<String, McpToolDefinition> serverDefinitions = new LinkedHashMap<>();
                discover(runtime, serverDefinitions);
                for (String exposedName : serverDefinitions.keySet()) {
                    if (nextDefinitions.containsKey(exposedName)) {
                        throw new IllegalStateException("MCP 工具暴露名称冲突: " + exposedName);
                    }
                }
                nextDefinitions.putAll(serverDefinitions);
                nextRuntimes.put(config.getMcpId(), runtime);
                log.info("MCP Runtime 加载成功: mcpId={}, toolCount={}", config.getMcpId(),
                        serverDefinitions.size());
            } catch (RuntimeException exception) {
                closeQuietly(runtime);
                log.error("MCP Runtime 加载失败并已隔离: mcpId={}, reason={}",
                        config == null ? null : config.getMcpId(), exception.getMessage(), exception);
            }
        }
        runtimes.values().forEach(this::closeQuietly);
        runtimes.clear();
        runtimes.putAll(nextRuntimes);
        definitions = List.copyOf(nextDefinitions.values());
    }

    public List<McpToolDefinition> listTools() {
        return definitions;
    }

    public McpSchema.CallToolResult callTool(McpToolDefinition definition, Map<String, Object> arguments) {
        if (definition == null) {
            throw new IllegalArgumentException("MCP 工具定义为空");
        }
        McpClientRuntime runtime;
        synchronized (this) {
            runtime = runtimes.get(definition.getMcpId());
        }
        if (runtime == null) {
            throw new IllegalStateException("MCP Runtime 不存在: " + definition.getMcpId());
        }
        runtime.getLock().lock();
        try {
            return runtime.getClient().callTool(new McpSchema.CallToolRequest(
                    definition.getRemoteToolName(), arguments == null ? Map.of() : arguments));
        } finally {
            runtime.getLock().unlock();
        }
    }

    private void discover(McpClientRuntime runtime, Map<String, McpToolDefinition> target) {
        runtime.getLock().lock();
        try {
            String cursor = null;
            do {
                McpSchema.ListToolsResult result = StringUtils.isBlank(cursor)
                        ? runtime.getClient().listTools() : runtime.getClient().listTools(cursor);
                if (result == null || result.tools() == null) {
                    return;
                }
                for (McpSchema.Tool tool : result.tools()) {
                    McpToolDefinition definition = toDefinition(runtime, tool);
                    McpToolDefinition previous = target.putIfAbsent(definition.getExposedToolName(), definition);
                    if (previous != null) {
                        throw new IllegalStateException("MCP 工具暴露名称冲突: " + definition.getExposedToolName());
                    }
                }
                cursor = result.nextCursor();
            } while (StringUtils.isNotBlank(cursor));
        } finally {
            runtime.getLock().unlock();
        }
    }

    private McpToolDefinition toDefinition(McpClientRuntime runtime, McpSchema.Tool tool) {
        if (tool == null || StringUtils.isBlank(tool.name())) {
            throw new IllegalArgumentException("MCP tools/list 返回空工具名: " + runtime.getMcpId());
        }
        return McpToolDefinition.builder()
                .mcpId(runtime.getMcpId())
                .remoteToolName(tool.name())
                .exposedToolName(exposedName(runtime.getMcpId(), tool.name()))
                .description(StringUtils.defaultIfBlank(tool.description(),
                        StringUtils.defaultIfBlank(tool.title(), "MCP tool " + tool.name())))
                .transportType(runtime.getTransportType())
                .inputSchema(toSchema(tool.inputSchema()))
                .build();
    }

    private Map<String, Object> toSchema(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return Map.of("type", "object", "properties", Map.of());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", StringUtils.defaultIfBlank(schema.type(), "object"));
        result.put("properties", schema.properties() == null ? Map.of() : schema.properties());
        if (schema.required() != null && !schema.required().isEmpty()) result.put("required", schema.required());
        if (schema.additionalProperties() != null) result.put("additionalProperties", schema.additionalProperties());
        if (schema.defs() != null && !schema.defs().isEmpty()) result.put("$defs", schema.defs());
        if (schema.definitions() != null && !schema.definitions().isEmpty()) result.put("definitions", schema.definitions());
        return result;
    }

    static String exposedName(String mcpId, String remoteToolName) {
        String prefix = "mcp__" + normalizeNamePart(mcpId) + "__";
        String remote = normalizeNamePart(remoteToolName);
        int remaining = MAX_TOOL_NAME_LENGTH - prefix.length();
        if (remaining < 1) {
            throw new IllegalArgumentException("mcpId 过长，无法生成 MCP 工具名: " + mcpId);
        }
        return prefix + remote.substring(0, Math.min(remote.length(), remaining));
    }

    private static String normalizeNamePart(String value) {
        String normalized = StringUtils.defaultString(value).trim().replaceAll("[^A-Za-z0-9_-]", "_");
        normalized = normalized.replaceAll("_+", "_");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("MCP 工具命名字段为空");
        }
        return normalized;
    }

    @PreDestroy
    public synchronized void close() {
        runtimes.values().forEach(this::closeQuietly);
        runtimes.clear();
        definitions = List.of();
    }

    private void closeQuietly(McpClientRuntime runtime) {
        if (runtime == null || runtime.getClient() == null) return;
        try {
            runtime.getClient().close();
        } catch (RuntimeException exception) {
            log.warn("关闭 MCP Runtime 失败: mcpId={}, reason={}", runtime.getMcpId(), exception.getMessage());
        }
    }
}
