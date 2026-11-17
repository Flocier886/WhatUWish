package com.flocier.domain.agent.runtime.agent.tool.rich.codeinterpreter;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolResponseEntity;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 3B 私有流协议、身份和步骤顺序校验。 */
@Slf4j
@Component
public class CodeInterpreterStreamClient {
    @Value("${conversation.material.bridge-token:}")
    private String bridgeToken;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @SuppressWarnings("unchecked")
    public PythonToolResponseEntity stream(String baseUrl, ToolInvokeRequest request, AgentContext context,
                                           MaterialFileAccessResolver.Prepared prepared,
                                           Consumer<Map<String, Object>> onEvent) {
        if (bridgeToken == null || bridgeToken.isBlank())
            return failure("material_bridge_unconfigured", "材料服务间认证尚未配置");
        try {
            Map<String, Object> payload = payload(request, context, prepared);
            String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedBase + "/api/v1/material/code-interpreter/stream"))
                    .timeout(Duration.ofSeconds(660))
                    .header("Content-Type", "application/json")
                    .header("X-Material-Bridge-Token", bridgeToken)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(payload))).build();
            HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                return failure("material_http_error", "代码分析服务返回状态 " + response.statusCode());
            }
            Map<String, Object> terminal = null;
            int sequence = 0;
            String frameType = null;
            String frameData = null;
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("代码分析已中断");
                    if (line.startsWith("event: ")) frameType = line.substring(7);
                    else if (line.startsWith("data: ")) frameData = line.substring(6);
                    else if (line.isBlank() && frameType != null) {
                        if (frameData == null || frameData.length() > 32_000 || terminal != null)
                            throw new IllegalArgumentException("代码分析流协议无效");
                        Map<String, Object> event = JSON.parseObject(frameData, Map.class);
                        if (event == null || !frameType.equals(event.get("type"))
                                || !Objects.equals(context.getSessionId(), event.get("sessionId"))
                                || !Objects.equals(context.getAttribute("runId"), event.get("runId"))
                                || !Objects.equals(request.getToolCallId(), event.get("toolCallId"))
                                || !(event.get("sequence") instanceof Number number)
                                || number.intValue() != ++sequence || sequence > 32)
                            throw new IllegalArgumentException("代码分析步骤身份或顺序无效");
                        if ("result".equals(frameType)) terminal = event;
                        else if (!"step".equals(frameType)) throw new IllegalArgumentException("代码分析事件类型无效");
                        onEvent.accept(event);
                        frameType = null;
                        frameData = null;
                    }
                }
            }
            if (terminal == null) return failure("code_interpreter_stream_terminated", "代码分析流缺少终态");
            PythonToolResponseEntity result = new PythonToolResponseEntity();
            result.setSuccess("success".equals(terminal.get("status")));
            result.setStatus(result.isSuccess() ? "success" : "failed");
            String conclusion = String.valueOf(terminal.getOrDefault("conclusion", ""));
            String error = String.valueOf(terminal.getOrDefault("error", ""));
            result.setOutput(result.isSuccess() ? conclusion : error);
            result.setLlmObservation(result.isSuccess() ? "代码分析完成：\n" + conclusion : "代码分析失败：" + error);
            result.setErrorCode(result.isSuccess() ? null : String.valueOf(terminal.getOrDefault("errorCode", "code_interpreter_failed")));
            result.setErrorMessage(result.isSuccess() ? null : error);
            result.setStructured(Map.of("stepCount", terminal.getOrDefault("stepCount", 0),
                    "durationMs", terminal.getOrDefault("durationMs", 0)));
            if (result.isSuccess() && terminal.get("materialOutputs") instanceof List<?> values) {
                List<Map<String, Object>> outputs = new ArrayList<>();
                for (Object value : values) {
                    if (!(value instanceof Map<?, ?> item)) throw new IllegalArgumentException("代码分析产物合同无效");
                    outputs.add((Map<String, Object>) item);
                }
                result.setMaterialOutputs(outputs);
            }
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failure("code_interpreter_interrupted", "代码分析已中断");
        } catch (Exception failure) {
            log.warn("code interpreter stream failed, toolCallId={}", request.getToolCallId(), failure);
            return failure("code_interpreter_stream_failed", "代码分析流中断或协议无效");
        }
    }

    private Map<String, Object> payload(ToolInvokeRequest request, AgentContext context,
                                        MaterialFileAccessResolver.Prepared prepared) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("toolName", "code_interpreter");
        root.put("arguments", prepared.arguments());
        root.put("workspaceRoot", prepared.workspaceRoot());
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put("sessionId", firstNonBlank(context.getSessionId(), request.getSessionId()));
        toolContext.put("runId", context.getAttribute("runId"));
        toolContext.put("requestId", firstNonBlank(context.getRequestId(), request.getRequestId()));
        toolContext.put("traceId", context.getTraceId());
        toolContext.put("toolCallId", request.getToolCallId());
        toolContext.put("step", request.getStep());
        toolContext.put("agentId", context.getAttribute("agentId"));
        root.put("context", toolContext);
        return root;
    }

    private PythonToolResponseEntity failure(String code, String message) {
        PythonToolResponseEntity response = new PythonToolResponseEntity();
        response.setSuccess(false);
        response.setErrorCode(code);
        response.setErrorMessage(message);
        response.setLlmObservation(message);
        return response;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
