package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.artifact.ArtifactReadAccessException;
import com.flocier.domain.agent.runtime.artifact.ArtifactReadAccessResolver;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * 受控 Agent Artifact 读取入口。
 * LLM 只使用 Artifact ID 和分段参数；存储 locator 仅在 Java 调 Python 时注入。
 */
@RequiredArgsConstructor
public class ArtifactReadTool implements BaseTool {

    private static final List<String> PUBLIC_ARGUMENTS = List.of("artifactId", "offset", "limit");
    private static final List<String> PUBLIC_STRUCTURED_FIELDS = List.of(
            "artifactId", "fileName", "contentLength", "offset", "returnedLength", "returnedBytes", "hasMore", "nextOffset"
    );

    private final String name;
    private final String description;
    private final Object params;
    private final String pythonService;
    private final String pythonEndpoint;
    private final PythonToolClient pythonToolClient;
    private final ArtifactReadAccessResolver accessResolver;

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
            return ToolResultPayload.failure(
                    "artifact_read_invalid_argument", "Artifact 读取参数无效。", "产物读取失败：调用参数无效。"
            );
        }
        if (!StringUtils.hasText(pythonService) || !StringUtils.hasText(pythonEndpoint)) {
            return ToolResultPayload.failure(
                    "artifact_read_config_missing", "Artifact 读取服务配置缺失。", "产物读取当前不可用。"
            );
        }
        try {
            Map<String, Object> publicArguments = request.getArguments() == null ? Map.of() : request.getArguments();
            if (publicArguments.keySet().stream().anyMatch(key -> !PUBLIC_ARGUMENTS.contains(key))) {
                return ToolResultPayload.failure(
                        "artifact_read_invalid_argument",
                        "Artifact 读取不接受内部存储定位参数。",
                        "产物读取失败：只需提供 Artifact ID 和可选的分段参数。"
                );
            }
            String artifactId = stringArgument(publicArguments, "artifactId");
            ArtifactLedgerEntity artifact = accessResolver.resolve(context, artifactId);
            ToolInvokeRequest privateRequest = privateRequest(request, publicArguments, artifact);
            PythonToolResponseEntity response = pythonToolClient.execute(
                    pythonService, pythonEndpoint, name, privateRequest, context
            );
            return project(response);
        } catch (ArtifactReadAccessException exception) {
            return ToolResultPayload.failure(exception.getErrorCode(), exception.getMessage(), "产物读取失败：" + exception.getMessage());
        }
    }

    private ToolInvokeRequest privateRequest(ToolInvokeRequest request,
                                             Map<String, Object> publicArguments,
                                             ArtifactLedgerEntity artifact) {
        Map<String, Object> privateArguments = new LinkedHashMap<>();
        privateArguments.put("artifactId", artifact.getArtifactId());
        privateArguments.put("storageType", artifact.getStorageType());
        privateArguments.put("storageKey", artifact.getStorageKey());
        copyIfPresent(publicArguments, privateArguments, "offset");
        copyIfPresent(publicArguments, privateArguments, "limit");
        return ToolInvokeRequest.builder()
                .toolName(request.getToolName())
                .toolCallId(request.getToolCallId())
                .arguments(privateArguments)
                .metadata(request.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getMetadata()))
                .sourceHint(request.getSourceHint())
                .requestId(request.getRequestId())
                .sessionId(request.getSessionId())
                .step(request.getStep())
                .build();
    }

    private ToolResultPayload project(PythonToolResponseEntity response) {
        if (response == null) {
            return ToolResultPayload.failure("artifact_read_upstream_failed", "Artifact 读取服务未返回结果。", "产物读取当前不可用。");
        }
        if (!response.isSuccess()) {
            return ToolResultPayload.failure(
                    firstNonBlank(response.getErrorCode(), "artifact_read_upstream_failed"),
                    firstNonBlank(response.getErrorMessage(), "Artifact 读取服务执行失败。"),
                    "产物读取失败：当前内容不可用或无法读取。"
            );
        }
        Map<String, Object> structured = publicStructured(response.getStructured());
        String observation = StringUtils.hasText(response.getLlmObservation())
                ? response.getLlmObservation()
                : response.getOutput();
        String output = StringUtils.hasText(response.getOutput()) ? response.getOutput() : observation;
        return ToolResultPayload.success(output, observation, structured);
    }

    private Map<String, Object> publicStructured(Map<String, Object> source) {
        Map<String, Object> projected = new LinkedHashMap<>();
        if (source == null) {
            return projected;
        }
        for (String field : PUBLIC_STRUCTURED_FIELDS) {
            if (source.containsKey(field)) {
                projected.put(field, source.get(field));
            }
        }
        return projected;
    }

    private String stringArgument(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }
}
