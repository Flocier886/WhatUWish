package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python 工具 HTTP 客户端。
 * 负责把 Java 侧工具调用请求发送给 FastAPI 服务，并解析统一响应结构。
 */
@Slf4j
@Component
public class PythonToolClient {
    @Value("${conversation.material.bridge-token:}")
    private String materialBridgeToken;
    @Value("${conversation.material.timeout-seconds:180}")
    private int materialTimeoutSeconds;
    @Value("${conversation.python-tool.web-search-timeout-seconds:120}")
    private int webSearchTimeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 调用 Python FastAPI 工具服务，并将结果解析为结构化响应对象。
     */
    public PythonToolResponseEntity execute(String baseUrl,
                                            String endpoint,
                                            String toolName,
                                            ToolInvokeRequest request,
                                            AgentContext context) {
        String url = normalizeUrl(baseUrl, endpoint);
        String requestBody = JSON.toJSONString(buildPayload(toolName, request, context));
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds("web_search".equals(toolName)
                            ? Math.max(15, webSearchTimeoutSeconds) : 15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return JSON.parseObject(response.body(), PythonToolResponseEntity.class);
            }
            log.warn("python tool http call failed, toolName={}, statusCode={}, body={}", toolName, response.statusCode(), response.body());
            PythonToolResponseEntity failed = new PythonToolResponseEntity();
            failed.setSuccess(false);
            failed.setErrorCode("python_tool_http_error");
            failed.setErrorMessage("Python 工具服务返回非成功状态: " + response.statusCode());
            failed.setMetadata(Map.of("responseBody", response.body()));
            return failed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("python tool call interrupted, toolName={}, url={}", toolName, url, e);
            PythonToolResponseEntity failed = new PythonToolResponseEntity();
            failed.setSuccess(false);
            failed.setErrorCode("python_tool_call_interrupted");
            failed.setErrorMessage(e.getMessage());
            return failed;
        } catch (IOException e) {
            log.error("python tool io call failed, toolName={}, url={}", toolName, url, e);
            PythonToolResponseEntity failed = new PythonToolResponseEntity();
            failed.setSuccess(false);
            failed.setErrorCode("python_tool_io_error");
            failed.setErrorMessage(e.getMessage());
            return failed;
        } catch (Exception e) {
            log.error("python tool call failed, toolName={}, url={}", toolName, url, e);
            PythonToolResponseEntity failed = new PythonToolResponseEntity();
            failed.setSuccess(false);
            failed.setErrorCode("python_tool_call_failed");
            failed.setErrorMessage(e.getMessage());
            return failed;
        }
    }

    /** 材料工具只传可信工作区路径与参数，保持独立的调用超时和服务认证。 */
    public PythonToolResponseEntity executeMaterial(String baseUrl, String toolName,
                                                     ToolInvokeRequest request, AgentContext context,
                                                     MaterialFileAccessResolver.Prepared prepared) {
        // 工作区根由 Java 注入；模型参数不能覆盖它。
        if (materialBridgeToken == null || materialBridgeToken.isBlank())
            return materialFailure("material_bridge_unconfigured", "材料服务间认证尚未配置");
        try {
            Map<String, Object> payload = buildPayload(toolName, request, context);
            payload.put("arguments", prepared.arguments());
            payload.put("workspaceRoot", prepared.workspaceRoot());
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeUrl(baseUrl, "/api/v1/material/execute")))
                    .timeout(Duration.ofSeconds(materialCallTimeout(toolName, request)))
                    .header("Content-Type", "application/json")
                    .header("X-Material-Bridge-Token", materialBridgeToken)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300)
                return JSON.parseObject(response.body(), PythonToolResponseEntity.class);
            return materialFailure("material_http_error", "材料服务返回状态 " + response.statusCode());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return materialFailure("material_interrupted", "材料处理已中断");
        } catch (Exception failure) {
            log.warn("material tool failed, toolName={}", toolName, failure);
            return materialFailure("material_transport_error", "材料服务不可用或处理超时");
        }
    }

    private int materialCallTimeout(String toolName, ToolInvokeRequest request) {
        if (com.flocier.domain.agent.runtime.agent.tool.docgen.DocGenContracts.all().stream()
                .anyMatch(tool -> tool.getName().equals(toolName)))
            return Math.max(240, materialTimeoutSeconds);
        if (!"bash".equals(toolName) || request == null || request.getArguments() == null)
            return Math.max(1, materialTimeoutSeconds);
        Object requested = request.getArguments().get("timeout_sec");
        int commandSeconds = requested instanceof Number number ? number.intValue() : 120;
        return Math.max(Math.max(1, materialTimeoutSeconds), Math.min(600, Math.max(1, commandSeconds)) + 60);
    }

    private PythonToolResponseEntity materialFailure(String code, String message) {
        PythonToolResponseEntity response = new PythonToolResponseEntity();
        response.setSuccess(false);
        response.setErrorCode(code);
        response.setErrorMessage(message);
        response.setLlmObservation(message);
        return response;
    }

    private Map<String, Object> buildPayload(String toolName, ToolInvokeRequest request, AgentContext context) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("toolName", toolName);
        root.put("arguments", request == null || request.getArguments() == null ? Map.of() : request.getArguments());

        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put("sessionId", firstNonBlank(
                context == null ? null : context.getSessionId(),
                request == null ? null : request.getSessionId()));
        toolContext.put("runId", context == null ? null : context.getAttribute("runId"));
        toolContext.put("requestId", firstNonBlank(
                context == null ? null : context.getRequestId(),
                request == null ? null : request.getRequestId()));
        toolContext.put("traceId", context == null ? null : context.getTraceId());
        toolContext.put("toolCallId", request == null ? null : request.getToolCallId());
        toolContext.put("step", request == null ? null : request.getStep());
        toolContext.put("agentId", context == null ? null : context.getAttribute("agentId"));
        root.put("context", toolContext);
        return root;
    }

    private String normalizeUrl(String baseUrl, String endpoint) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return normalizedBase + normalizedEndpoint;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
