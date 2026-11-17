package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python 工具适配器。
 * 负责把 Java 侧 ToolInvokeRequest 转发到 Python FastAPI，并直接映射工具自带结果协议。
 */
@RequiredArgsConstructor
public class PythonToolAdapter implements BaseTool {

    private final String name;
    private final String description;
    private final Object params;
    private final String pythonService;
    private final String pythonEndpoint;
    private final PythonToolClient pythonToolClient;

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Object params() {
        return params;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request)) {
            return failed(
                    "invalid_python_tool_input",
                    "PythonToolAdapter only accepts ToolInvokeRequest input",
                    "Python 工具调用入参无效，无法继续执行。"
            );
        }
        if (!StringUtils.hasText(pythonService) || !StringUtils.hasText(pythonEndpoint)) {
            return failed(
                    "python_tool_config_missing",
                    "Python tool service url or endpoint is missing",
                    "Python 工具服务配置缺失，当前无法调用该工具。"
            );
        }

        PythonToolResponseEntity response = pythonToolClient.execute(
                pythonService,
                pythonEndpoint,
                name,
                request,
                context
        );
        return ToolResultPayload.builder()
                .success(response.isSuccess())
                .status(resolveStatus(response))
                .toolResult(resolveToolResult(response))
                .llmObservation(resolveObservation(response))
                .structuredOutput(response.getStructured() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.getStructured()))
                .ledgerFacts(copyLedgerFacts(response.getLedgerFacts()))
                .artifactRefs(resolveArtifactRefs(response.getArtifacts()))
                .artifactDescriptors(copyArtifactDescriptors(response.getArtifacts()))
                .errorCode(response.getErrorCode())
                .errorMessage(response.getErrorMessage())
                .metadata(response.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.getMetadata()))
                .build();
    }

    private String resolveStatus(PythonToolResponseEntity response) {
        if (response == null) {
            return "failed";
        }
        if (StringUtils.hasText(response.getStatus())) {
            return response.getStatus().trim();
        }
        return response.isSuccess() ? "success" : "failed";
    }

    private String resolveToolResult(PythonToolResponseEntity response) {
        if (response == null) {
            return "";
        }
        if (StringUtils.hasText(response.getOutput())) {
            return response.getOutput();
        }
        return firstNonBlank(response.getLlmObservation(), response.getErrorMessage());
    }

    private String resolveObservation(PythonToolResponseEntity response) {
        if (response == null) {
            return "";
        }
        if (StringUtils.hasText(response.getLlmObservation())) {
            return response.getLlmObservation();
        }
        if (response.isSuccess()) {
            if (StringUtils.hasText(response.getOutput())) {
                return response.getOutput();
            }
            return "Python 工具已完成，但未提供可供下一步决策的 observation。";
        }
        return StringUtils.hasText(response.getErrorMessage()) ? response.getErrorMessage() : "Python tool execution failed";
    }

    private List<Map<String, Object>> copyLedgerFacts(List<Map<String, Object>> ledgerFacts) {
        if (ledgerFacts == null || ledgerFacts.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copied = new java.util.ArrayList<>();
        for (Map<String, Object> item : ledgerFacts) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            copied.add(new LinkedHashMap<>(item));
        }
        return copied;
    }

    private List<String> resolveArtifactRefs(List<Map<String, Object>> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        return artifacts.stream()
                .map(item -> item == null ? null : firstNonBlank(
                        item.get("artifactRef") == null ? null : String.valueOf(item.get("artifactRef")),
                        item.get("storageRef") == null ? null : String.valueOf(item.get("storageRef")),
                        item.get("path") == null ? null : String.valueOf(item.get("path")),
                        item.get("fileName") == null ? null : String.valueOf(item.get("fileName")),
                        item.get("name") == null ? null : String.valueOf(item.get("name"))
                ))
                .filter(java.util.Objects::nonNull)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<Map<String, Object>> copyArtifactDescriptors(List<Map<String, Object>> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copied = new java.util.ArrayList<>();
        for (Map<String, Object> artifact : artifacts) {
            if (artifact != null && !artifact.isEmpty()) {
                copied.add(new LinkedHashMap<>(artifact));
            }
        }
        return copied;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private ToolResultPayload failed(String errorCode, String errorMessage, String llmObservation) {
        return ToolResultPayload.builder()
                .success(false)
                .status("failed")
                .toolResult(errorMessage)
                .llmObservation(llmObservation)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .structuredOutput(new LinkedHashMap<>())
                .ledgerFacts(List.of())
                .artifactRefs(List.of())
                .metadata(new LinkedHashMap<>())
                .build();
    }
}
