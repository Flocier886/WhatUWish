package com.flocier.domain.agent.runtime.artifact.content;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 生成与文件名、Session 和 Run 路径解耦的 v1 Artifact object key。 */
public class ArtifactObjectKeyBuilder {
    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private final String prefix;

    public ArtifactObjectKeyBuilder(String optionalPrefix) {
        this.prefix = normalizePrefix(optionalPrefix);
    }

    public String build(String artifactId) {
        String safeId = validateArtifactId(artifactId);
        return prefix.isEmpty()
                ? "v1/artifacts/" + safeId + "/content"
                : prefix + "/v1/artifacts/" + safeId + "/content";
    }

    public String validateStorageKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.startsWith("/") || storageKey.contains("\\")) {
            throw new IllegalArgumentException("Artifact object key 无效");
        }
        String[] parts = storageKey.split("/", -1);
        List<String> expectedPrefix = prefix.isEmpty() ? List.of() : List.of(prefix.split("/"));
        if (parts.length != expectedPrefix.size() + 4) {
            throw new IllegalArgumentException("Artifact object key 无效");
        }
        for (int index = 0; index < expectedPrefix.size(); index++) {
            if (!expectedPrefix.get(index).equals(parts[index])) {
                throw new IllegalArgumentException("Artifact object key 无效");
            }
        }
        int offset = expectedPrefix.size();
        if (!"v1".equals(parts[offset]) || !"artifacts".equals(parts[offset + 1])
                || !"content".equals(parts[offset + 3])) {
            throw new IllegalArgumentException("Artifact object key 无效");
        }
        validateArtifactId(parts[offset + 2]);
        return storageKey;
    }

    public String getPrefix() {
        return prefix;
    }

    private String validateArtifactId(String artifactId) {
        String value = artifactId == null ? "" : artifactId.trim();
        if (!ARTIFACT_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("artifactId 无效");
        }
        return value;
    }

    private String normalizePrefix(String optionalPrefix) {
        String value = optionalPrefix == null ? "" : optionalPrefix.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("/") || value.endsWith("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Artifact object prefix 无效");
        }
        String[] parts = value.split("/", -1);
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Artifact object prefix 无效");
            }
            normalized.add(part);
        }
        return String.join("/", normalized);
    }
}
