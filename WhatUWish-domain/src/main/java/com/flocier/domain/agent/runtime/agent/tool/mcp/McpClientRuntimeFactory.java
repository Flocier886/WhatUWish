package com.flocier.domain.agent.runtime.agent.tool.mcp;

import com.flocier.domain.agent.model.vo.AiClientToolMcpVO;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 只负责使用官方 MCP SDK 创建和初始化三种 Transport。 */
@Component
public class McpClientRuntimeFactory {

    public McpClientRuntime create(AiClientToolMcpVO config) {
        if (config == null || StringUtils.isBlank(config.getMcpId())) {
            throw new IllegalArgumentException("MCP 配置或 mcpId 为空");
        }
        String transportType = normalizeTransportType(config.getTransportType());
        McpClientTransport transport = switch (transportType) {
            case "sse" -> createSseTransport(config);
            case "stdio" -> createStdioTransport(config);
            case "streamable_http" -> createStreamableHttpTransport(config);
            default -> throw new IllegalArgumentException("不支持的 MCP transportType: " + config.getTransportType());
        };
        Duration timeout = Duration.ofMinutes(Math.max(1, defaultTimeout(config.getRequestTimeout())));
        McpSyncClient client = McpClient.sync(transport).requestTimeout(timeout).build();
        try {
            client.initialize();
            return new McpClientRuntime(config.getMcpId(), transportType, client);
        } catch (RuntimeException exception) {
            closeQuietly(client);
            throw exception;
        }
    }

    private McpClientTransport createSseTransport(AiClientToolMcpVO config) {
        AiClientToolMcpVO.TransportConfigSse transport = config.getTransportConfigSse();
        if (transport == null || StringUtils.isBlank(transport.getBaseUri())) {
            throw new IllegalArgumentException("MCP SSE baseUri 为空: " + config.getMcpId());
        }
        HttpLocation location = resolveHttpLocation(transport.getBaseUri(), transport.getSseEndpoint(), "/sse");
        return HttpClientSseClientTransport.builder(location.baseUri())
                .sseEndpoint(location.endpoint())
                .build();
    }

    private McpClientTransport createStdioTransport(AiClientToolMcpVO config) {
        AiClientToolMcpVO.TransportConfigStdio transport = config.getTransportConfigStdio();
        Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> entries =
                transport == null || transport.getStdio() == null ? Collections.emptyMap() : transport.getStdio();
        AiClientToolMcpVO.TransportConfigStdio.Stdio stdio = entries.get(config.getMcpName());
        if (stdio == null && entries.size() == 1) {
            stdio = entries.values().iterator().next();
        }
        if (stdio == null || StringUtils.isBlank(stdio.getCommand())) {
            throw new IllegalArgumentException("MCP STDIO command 为空: " + config.getMcpId());
        }
        ServerParameters parameters = ServerParameters.builder(stdio.getCommand())
                .args(stdio.getArgs() == null ? List.of() : stdio.getArgs())
                .env(stdio.getEnv() == null ? Map.of() : stdio.getEnv())
                .build();
        return new StdioClientTransport(parameters, McpJsonMapper.getDefault());
    }

    private McpClientTransport createStreamableHttpTransport(AiClientToolMcpVO config) {
        AiClientToolMcpVO.TransportConfigStreamableHttp transport = config.getTransportConfigStreamableHttp();
        if (transport == null || StringUtils.isBlank(transport.getBaseUri())) {
            throw new IllegalArgumentException("MCP Streamable HTTP baseUri 为空: " + config.getMcpId());
        }
        HttpLocation location = resolveHttpLocation(transport.getBaseUri(), transport.getEndPoint(), "/mcp");
        Map<String, String> headers = transport.getHeaders() == null ? Map.of() : transport.getHeaders();
        return HttpClientStreamableHttpTransport.builder(location.baseUri())
                .endpoint(location.endpoint())
                .httpRequestCustomizer((builder, method, endpoint, body, context) -> headers.forEach(builder::header))
                .build();
    }

    private HttpLocation resolveHttpLocation(String configuredBaseUri, String configuredEndpoint, String defaultEndpoint) {
        URI uri = URI.create(configuredBaseUri.trim());
        if (uri.getScheme() == null || uri.getAuthority() == null) {
            throw new IllegalArgumentException("MCP HTTP baseUri 无效: " + configuredBaseUri);
        }
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        String uriPath = StringUtils.defaultIfBlank(uri.getRawPath(), "");
        String endpoint = StringUtils.defaultIfBlank(configuredEndpoint,
                StringUtils.defaultIfBlank(uriPath, defaultEndpoint));
        boolean configuredAsFullUrl = StringUtils.isNotBlank(uriPath) && !"/".equals(uriPath)
                && (StringUtils.isBlank(configuredEndpoint) || uriPath.equals(configuredEndpoint));
        String baseUri = configuredAsFullUrl ? origin : configuredBaseUri;
        if (StringUtils.isNotBlank(uri.getRawQuery()) && !endpoint.contains("?")) {
            endpoint += "?" + uri.getRawQuery();
        }
        return new HttpLocation(baseUri, endpoint);
    }

    private String normalizeTransportType(String value) {
        if ("streamableHttp".equalsIgnoreCase(value) || "streamable_http".equalsIgnoreCase(value)) {
            return "streamable_http";
        }
        return StringUtils.defaultString(value).trim().toLowerCase();
    }

    private int defaultTimeout(Integer timeout) {
        return timeout == null ? 3 : timeout;
    }

    private void closeQuietly(McpSyncClient client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // 初始化失败时只做资源兜底，不覆盖原始异常。
        }
    }

    private record HttpLocation(String baseUri, String endpoint) { }
}
