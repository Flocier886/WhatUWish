package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从工具结果中收集“工具明确声明”的运行时产物。
 * 边界：只识别 artifacts / generatedArtifacts / map 型 artifactRefs，不从普通 observation 或普通 structuredOutput 猜产物。
 */
@Component
public class ArtifactRuntimeCollector {

    public List<ArtifactRuntimeDescriptor> collect(ToolInvokeRequest request,
                                                   ToolExecutionResult result,
                                                   AgentContext context) {
        if (result == null) {
            return List.of();
        }
        List<ArtifactRuntimeDescriptor> collected = new ArrayList<>();
        addArtifactMaps(result.getArtifactDescriptors(), request, result, collected);
        collectFromStructuredOutput(result.getStructuredOutput(), request, result, collected);
        return List.copyOf(collected);
    }

    private void collectFromStructuredOutput(Object structuredOutput,
                                             ToolInvokeRequest request,
                                             ToolExecutionResult result,
                                             List<ArtifactRuntimeDescriptor> target) {
        if (!(structuredOutput instanceof Map<?, ?> map)) {
            return;
        }
        addArtifactList(map.get("generatedArtifacts"), request, result, target);
        addArtifactList(map.get("artifacts"), request, result, target);
        addArtifactMap(map.get("artifactRefs"), request, result, target);
    }

    private void addArtifactList(Object rawList,
                                 ToolInvokeRequest request,
                                 ToolExecutionResult result,
                                 List<ArtifactRuntimeDescriptor> target) {
        if (!(rawList instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                ArtifactRuntimeDescriptor descriptor = fromMap(map, request, result);
                if (descriptor != null) {
                    target.add(descriptor);
                }
            }
        }
    }

    private void addArtifactMap(Object rawMap,
                                ToolInvokeRequest request,
                                ToolExecutionResult result,
                                List<ArtifactRuntimeDescriptor> target) {
        if (!(rawMap instanceof Map<?, ?> map)) {
            return;
        }
        ArtifactRuntimeDescriptor descriptor = fromMap(map, request, result);
        if (descriptor != null) {
            target.add(descriptor);
        }
    }

    private void addArtifactMaps(List<Map<String, Object>> maps,
                                 ToolInvokeRequest request,
                                 ToolExecutionResult result,
                                 List<ArtifactRuntimeDescriptor> target) {
        if (maps == null || maps.isEmpty()) {
            return;
        }
        for (Map<String, Object> map : maps) {
            ArtifactRuntimeDescriptor descriptor = fromMap(map, request, result);
            if (descriptor != null) {
                target.add(descriptor);
            }
        }
    }

    private ArtifactRuntimeDescriptor fromMap(Map<?, ?> raw,
                                              ToolInvokeRequest request,
                                              ToolExecutionResult result) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Map<String, Object> map = copyMap(raw);
        String artifactId = stringValue(map.get("artifactId"));
        String storageKey = stringValue(map.get("storageKey"));
        String downloadUrl = stringValue(map.get("downloadUrl"));
        String previewUrl = stringValue(map.get("previewUrl"));
        String storageRef = stringValue(map.get("storageRef"));
        String stableRef = firstNonBlank(storageRef, storageKey, downloadUrl, previewUrl);
        String fileName = stringValue(map.get("fileName"));
        String artifactType = firstNonBlank(
                stringValue(map.get("artifactType")),
                stringValue(map.get("type"))
        );
        String artifactRole = stringValue(map.get("artifactRole"));
        String visibility = stringValue(map.get("visibility"));
        String sourceType = stringValue(map.get("sourceType"));
        String sourceName = stringValue(map.get("sourceName"));
        String storageType = stringValue(map.get("storageType"));
        String contentSummary = firstNonBlank(
                stringValue(map.get("contentSummary")),
                stringValue(map.get("summary"))
        );
        String mimeType = stringValue(map.get("mimeType"));
        Long fileSize = longValue(firstNonNull(map.get("fileSize"), map.get("size")));
        if (!isCompleteDescriptor(
                artifactId,
                artifactType,
                artifactRole,
                visibility,
                sourceType,
                sourceName,
                fileName,
                storageType,
                stableRef,
                contentSummary,
                mimeType,
                fileSize
        )) {
            return null;
        }
        return ArtifactRuntimeDescriptor.builder()
                .artifactId(artifactId)
                .name(firstNonBlank(stringValue(map.get("name")), stringValue(map.get("artifactName")), fileName))
                .artifactType(artifactType)
                .artifactRole(artifactRole)
                .visibility(visibility)
                .sourceType(sourceType)
                .sourceId(firstNonBlank(stringValue(map.get("sourceId")), stringValue(map.get("skillExecutionId"))))
                .sourceName(sourceName)
                .fileName(fileName)
                .storageType(storageType)
                .storageRef(storageRef)
                .storageKey(storageKey)
                .downloadUrl(downloadUrl)
                .previewUrl(previewUrl)
                .contentSummary(contentSummary)
                .mimeType(mimeType)
                .fileSize(fileSize)
                .fileHash(stringValue(map.get("fileHash")))
                .metadata(metadata(map))
                .build();
    }

    private boolean isCompleteDescriptor(String artifactId,
                                         String artifactType,
                                         String artifactRole,
                                         String visibility,
                                         String sourceType,
                                         String sourceName,
                                         String fileName,
                                         String storageType,
                                         String stableRef,
                                         String contentSummary,
                                         String mimeType,
                                         Long fileSize) {
        return StringUtils.isNotBlank(artifactId)
                && StringUtils.isNotBlank(artifactType)
                && StringUtils.isNotBlank(artifactRole)
                && StringUtils.isNotBlank(visibility)
                && StringUtils.isNotBlank(sourceType)
                && StringUtils.isNotBlank(sourceName)
                && StringUtils.isNotBlank(fileName)
                && StringUtils.isNotBlank(storageType)
                && StringUtils.isNotBlank(stableRef)
                && StringUtils.isNotBlank(contentSummary)
                && StringUtils.isNotBlank(mimeType)
                && fileSize != null
                && fileSize >= 0;
    }

    private Map<String, Object> metadata(Map<String, Object> map) {
        Object rawMetadata = map.get("metadata");
        if (rawMetadata instanceof Map<?, ?> metadata) {
            return copyMap(metadata);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }


    private Object firstNonNull(Object... candidates) {
        if (candidates == null) {
            return null;
        }
        for (Object candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (StringUtils.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
