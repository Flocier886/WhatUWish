package com.flocier.domain.agent.runtime.artifact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具运行时明确声明的可复用产物。
 * 它是工具响应协议到 artifact ledger 之间的中间模型，不承载普通 observation 或 structuredOutput。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactRuntimeDescriptor {

    private String artifactId;
    private String name;
    private String artifactType;
    private String artifactRole;
    private String visibility;
    private String sourceType;
    private String sourceId;
    private String sourceName;
    private String fileName;
    private String storageType;
    private String storageRef;
    private String storageKey;
    private String downloadUrl;
    private String previewUrl;
    private String contentSummary;
    private String mimeType;
    private Long fileSize;
    private String fileHash;
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String stableRef() {
        if (hasText(storageKey)) {
            return storageKey;
        }
        if (hasText(storageRef)) {
            return storageRef;
        }
        if (hasText(downloadUrl)) {
            return downloadUrl;
        }
        if (hasText(previewUrl)) {
            return previewUrl;
        }
        return null;
    }

    public boolean hasStableLocation() {
        return hasText(stableRef());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
