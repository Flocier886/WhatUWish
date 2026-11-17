package com.flocier.domain.agent.runtime.agent.tool.mcp;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 将 MCP Content 映射为项目现有 observation、Ledger 与 Artifact 合同。 */
@Component
@RequiredArgsConstructor
public class McpToolResultMapper {
    private static final int OBSERVATION_LIMIT = 6000;
    private static final int TEXT_ITEM_LIMIT = 4000;
    private static final int STRUCTURED_STRING_LIMIT = 2000;
    private static final int STRUCTURED_COLLECTION_LIMIT = 50;
    private static final int MAX_BINARY_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TOTAL_BINARY_BYTES = 20 * 1024 * 1024;

    private final ArtifactContentWriter artifactContentWriter;

    @Value("${artifact.content.write.storage-type:local_artifact_storage}")
    private String writeStorageType;

    @Value("${artifact.content.write.prefix:}")
    private String writePrefix;

    public ToolResultPayload map(McpToolDefinition definition, McpSchema.CallToolResult result) {
        if (result == null) {
            return failure(definition, "mcp_empty_result", "MCP Server 未返回结果");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            String message = errorText(result.content());
            return failure(definition, "mcp_tool_error",
                    StringUtils.defaultIfBlank(message, "MCP Server 报告工具执行失败"));
        }

        MappingState state = new MappingState();
        for (McpSchema.Content content : safeContents(result.content())) {
            mapContent(definition, content, state);
        }
        Object structured = sanitize(result.structuredContent(), 0);
        if (structured != null) {
            state.structuredOutput.put("structuredContent", structured);
            state.observationParts.add("结构化结果：" + limit(JSON.toJSONString(structured), TEXT_ITEM_LIMIT));
        }
        state.structuredOutput.put("contents", List.copyOf(state.contentSummaries));
        state.structuredOutput.put("artifactIds", List.copyOf(state.artifactRefs));

        boolean partial = !state.binaryErrors.isEmpty();
        boolean hasUsableResult = !state.observationParts.isEmpty() || structured != null || !state.artifactRefs.isEmpty();
        if (!hasUsableResult && partial) {
            return failure(definition, "mcp_content_unavailable", String.join("；", state.binaryErrors));
        }
        if (!hasUsableResult) {
            state.observationParts.add("MCP 工具已成功执行，但未返回可消费内容。");
        }
        if (partial) {
            state.observationParts.add("部分二进制内容未能交付：" + String.join("；", state.binaryErrors));
        }

        String observation = limit(String.join("\n", state.observationParts), OBSERVATION_LIMIT);
        return ToolResultPayload.builder()
                .success(true)
                .status(partial ? "partial_success" : "success")
                .toolResult(observation)
                .llmObservation(observation)
                .structuredOutput(state.structuredOutput)
                .ledgerFacts(List.of(ledgerFact(definition, state, partial ? "partial_success" : "success")))
                .artifactRefs(List.copyOf(state.artifactRefs))
                .artifactDescriptors(List.copyOf(state.artifactDescriptors))
                .metadata(toolMetadata(definition))
                .build();
    }

    public ToolResultPayload mapException(McpToolDefinition definition, RuntimeException exception) {
        String detail = exception == null ? "未知异常" : StringUtils.defaultIfBlank(exception.getMessage(), exception.getClass().getSimpleName());
        return failure(definition, "mcp_call_failed", "MCP 工具调用异常：" + limit(detail, 800));
    }

    private void mapContent(McpToolDefinition definition, McpSchema.Content content, MappingState state) {
        if (content instanceof McpSchema.TextContent text) {
            addText(state, "文本结果", text.text(), "text");
            return;
        }
        if (content instanceof McpSchema.ResourceLink link) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("type", "resource_link");
            put(summary, "name", link.name());
            put(summary, "title", link.title());
            put(summary, "uri", link.uri());
            put(summary, "description", limit(link.description(), 800));
            put(summary, "mimeType", link.mimeType());
            put(summary, "size", link.size());
            state.contentSummaries.add(summary);
            state.observationParts.add("资源链接：" + limit(JSON.toJSONString(summary), 1200));
            return;
        }
        if (content instanceof McpSchema.EmbeddedResource embedded) {
            if (embedded.resource() instanceof McpSchema.TextResourceContents textResource) {
                addText(state, "文本资源 " + StringUtils.defaultString(textResource.uri()),
                        textResource.text(), "text_resource");
            } else if (embedded.resource() instanceof McpSchema.BlobResourceContents blob) {
                persistBinary(definition, blob.blob(), blob.mimeType(), "blob", blob.uri(), state);
            }
            return;
        }
        if (content instanceof McpSchema.ImageContent image) {
            persistBinary(definition, image.data(), image.mimeType(), "image", null, state);
            return;
        }
        if (content instanceof McpSchema.AudioContent audio) {
            persistBinary(definition, audio.data(), audio.mimeType(), "audio", null, state);
            return;
        }
        Map<String, Object> unknown = Map.of("type", StringUtils.defaultIfBlank(content.type(), "unknown"));
        state.contentSummaries.add(unknown);
        state.observationParts.add("MCP 返回了暂未展开的内容类型：" + unknown.get("type"));
    }

    private void addText(MappingState state, String label, String value, String type) {
        String safe = StringUtils.defaultString(value);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", type);
        summary.put("length", safe.length());
        summary.put("preview", limit(safe, 500));
        state.contentSummaries.add(summary);
        state.observationParts.add(label + "：" + limit(safe, TEXT_ITEM_LIMIT));
    }

    private void persistBinary(McpToolDefinition definition,
                               String encoded,
                               String mimeType,
                               String contentType,
                               String resourceUri,
                               MappingState state) {
        try {
            validateMime(contentType, mimeType);
            if (encoded == null || encoded.length() > ((MAX_BINARY_BYTES * 4L / 3L) + 16L)) {
                throw new IllegalArgumentException("Base64 为空或超过单文件限制");
            }
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > MAX_BINARY_BYTES
                    || state.totalBinaryBytes + bytes.length > MAX_TOTAL_BINARY_BYTES) {
                throw new IllegalArgumentException("二进制内容为空或超过大小限制");
            }
            validateSignature(mimeType, bytes);
            String artifactId = UUID.randomUUID().toString();
            String storageKey = new ArtifactObjectKeyBuilder(writePrefix).build(artifactId);
            String fileName = fileName(definition, contentType, resourceUri, mimeType, state.artifactRefs.size());
            ArtifactStoredContent stored = artifactContentWriter.write(ArtifactContentWriteRequest.builder()
                    .storageType(writeStorageType)
                    .storageKey(storageKey)
                    .content(bytes)
                    .mimeType(mimeType)
                    .build());
            if (stored == null || stored.getSize() != bytes.length) {
                throw new IllegalStateException("Artifact Storage 未确认完整写入");
            }
            String summary = "MCP 工具 " + definition.getExposedToolName() + " 返回的 " + contentType + " 文件";
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("artifactId", artifactId);
            descriptor.put("name", fileName);
            descriptor.put("artifactType", artifactType(contentType));
            descriptor.put("artifactRole", "output");
            descriptor.put("visibility", "visible");
            descriptor.put("sourceType", "mcp");
            descriptor.put("sourceId", definition.getMcpId() + ":" + definition.getRemoteToolName());
            descriptor.put("sourceName", definition.getExposedToolName());
            descriptor.put("fileName", fileName);
            descriptor.put("storageType", writeStorageType);
            descriptor.put("storageKey", storageKey);
            if (ArtifactStorageType.LOCAL.equals(writeStorageType)) descriptor.put("storageRef", storageKey);
            descriptor.put("contentSummary", summary);
            descriptor.put("mimeType", mimeType);
            descriptor.put("fileSize", stored.getSize());
            descriptor.put("fileHash", sha256(bytes));
            descriptor.put("metadata", Map.of(
                    "mcpId", definition.getMcpId(),
                    "remoteToolName", definition.getRemoteToolName(),
                    "mcpContentType", contentType
            ));
            state.artifactDescriptors.add(descriptor);
            state.artifactRefs.add(artifactId);
            state.totalBinaryBytes += bytes.length;

            Map<String, Object> contentSummary = new LinkedHashMap<>();
            contentSummary.put("type", contentType);
            contentSummary.put("artifactId", artifactId);
            contentSummary.put("fileName", fileName);
            contentSummary.put("mimeType", mimeType);
            contentSummary.put("size", stored.getSize());
            state.contentSummaries.add(contentSummary);
            state.observationParts.add("已生成 Artifact：artifactId=" + artifactId
                    + "，fileName=" + fileName + "，mimeType=" + mimeType + "，size=" + stored.getSize());
        } catch (RuntimeException exception) {
            state.binaryErrors.add(contentType + " 内容处理失败（" + safeReason(exception) + "）");
        }
    }

    private ToolResultPayload failure(McpToolDefinition definition, String errorCode, String message) {
        String observation = "MCP 工具执行失败：" + limit(message, 1200);
        return ToolResultPayload.builder()
                .success(false)
                .status("failed")
                .errorCode(errorCode)
                .errorMessage(limit(message, 1200))
                .toolResult(observation)
                .llmObservation(observation)
                .structuredOutput(Map.of("errorCode", errorCode))
                .ledgerFacts(List.of(Map.of(
                        "factType", "mcp_tool_call",
                        "mcpId", definition == null ? "unknown" : definition.getMcpId(),
                        "remoteToolName", definition == null ? "unknown" : definition.getRemoteToolName(),
                        "exposedToolName", definition == null ? "unknown" : definition.getExposedToolName(),
                        "status", "failed",
                        "errorCode", errorCode
                )))
                .metadata(toolMetadata(definition))
                .build();
    }

    private Map<String, Object> ledgerFact(McpToolDefinition definition, MappingState state, String status) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("factType", "mcp_tool_call");
        fact.put("mcpId", definition.getMcpId());
        fact.put("remoteToolName", definition.getRemoteToolName());
        fact.put("exposedToolName", definition.getExposedToolName());
        fact.put("transportType", definition.getTransportType());
        fact.put("status", status);
        fact.put("contentCount", state.contentSummaries.size());
        fact.put("artifactCount", state.artifactRefs.size());
        fact.put("contentTypes", state.contentSummaries.stream().map(item -> item.get("type")).distinct().toList());
        return fact;
    }

    private Map<String, Object> toolMetadata(McpToolDefinition definition) {
        if (definition == null) return Map.of("sourceType", "mcp");
        return Map.of(
                "sourceType", "mcp",
                "mcpId", definition.getMcpId(),
                "remoteToolName", definition.getRemoteToolName(),
                "exposedToolName", definition.getExposedToolName(),
                "transportType", definition.getTransportType()
        );
    }

    private Object sanitize(Object value, int depth) {
        if (value == null || depth > 8) return null;
        if (value instanceof String text) return limit(text, STRUCTURED_STRING_LIMIT);
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= STRUCTURED_COLLECTION_LIMIT) break;
                String key = String.valueOf(entry.getKey());
                if (isSensitiveStructuredKey(key)) continue;
                Object child = sanitize(entry.getValue(), depth + 1);
                if (child != null) safe.put(key, child);
            }
            return safe;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> safe = new ArrayList<>();
            int count = 0;
            for (Object item : collection) {
                if (count++ >= STRUCTURED_COLLECTION_LIMIT) break;
                Object child = sanitize(item, depth + 1);
                if (child != null) safe.add(child);
            }
            return safe;
        }
        return limit(String.valueOf(value), STRUCTURED_STRING_LIMIT);
    }

    private boolean isSensitiveStructuredKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.equals("data") || normalized.equals("blob") || normalized.contains("base64")
                || normalized.equals("meta") || normalized.equals("storagekey") || normalized.equals("storageref");
    }

    private String errorText(List<McpSchema.Content> contents) {
        List<String> texts = new ArrayList<>();
        for (McpSchema.Content content : safeContents(contents)) {
            if (content instanceof McpSchema.TextContent text && StringUtils.isNotBlank(text.text())) {
                texts.add(limit(text.text(), TEXT_ITEM_LIMIT));
            } else if (content instanceof McpSchema.EmbeddedResource embedded
                    && embedded.resource() instanceof McpSchema.TextResourceContents resource
                    && StringUtils.isNotBlank(resource.text())) {
                texts.add(limit(resource.text(), TEXT_ITEM_LIMIT));
            }
        }
        return limit(String.join("\n", texts), OBSERVATION_LIMIT);
    }

    private List<McpSchema.Content> safeContents(List<McpSchema.Content> contents) {
        return contents == null ? List.of() : contents;
    }

    private void validateMime(String contentType, String mimeType) {
        if (StringUtils.isBlank(mimeType) || mimeType.length() > 160 || mimeType.contains("\r") || mimeType.contains("\n")) {
            throw new IllegalArgumentException("MIME 无效");
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        if ("image".equals(contentType) && !normalized.startsWith("image/")) {
            throw new IllegalArgumentException("图片 MIME 无效");
        }
        if ("audio".equals(contentType) && !normalized.startsWith("audio/")) {
            throw new IllegalArgumentException("音频 MIME 无效");
        }
    }

    private void validateSignature(String mimeType, byte[] bytes) {
        String normalized = StringUtils.defaultString(mimeType).toLowerCase(Locale.ROOT).split(";", 2)[0];
        boolean valid = switch (normalized) {
            case "image/png" -> startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "image/gif" -> startsWith(bytes, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    || startsWith(bytes, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "image/webp" -> startsWith(bytes, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    && startsWith(bytes, 8, "WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "audio/wav", "audio/x-wav" -> startsWith(bytes, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    && startsWith(bytes, 8, "WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "audio/ogg" -> startsWith(bytes, "OggS".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "audio/mpeg" -> startsWith(bytes, "ID3".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    || (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xe0) == 0xe0);
            case "application/pdf" -> startsWith(bytes, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("二进制内容与声明 MIME 不匹配");
        }
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return startsWith(value, 0, prefix);
    }

    private boolean startsWith(byte[] value, int offset, byte[] prefix) {
        if (value == null || prefix == null || offset < 0 || value.length - offset < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[offset + index] != prefix[index]) return false;
        }
        return true;
    }

    private String fileName(McpToolDefinition definition, String contentType, String resourceUri,
                            String mimeType, int index) {
        String candidate = null;
        if (StringUtils.isNotBlank(resourceUri)) {
            int slash = Math.max(resourceUri.lastIndexOf('/'), resourceUri.lastIndexOf('\\'));
            candidate = slash >= 0 ? resourceUri.substring(slash + 1) : resourceUri;
            int query = candidate.indexOf('?');
            if (query >= 0) candidate = candidate.substring(0, query);
        }
        if (StringUtils.isBlank(candidate) || candidate.length() > 120) {
            candidate = definition.getRemoteToolName() + "-" + contentType + "-" + (index + 1) + extension(mimeType);
        }
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!candidate.contains(".")) candidate += extension(mimeType);
        return StringUtils.defaultIfBlank(candidate, "mcp-output-" + (index + 1) + extension(mimeType));
    }

    private String extension(String mimeType) {
        String normalized = StringUtils.defaultString(mimeType).toLowerCase(Locale.ROOT).split(";", 2)[0];
        return switch (normalized) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "audio/mpeg" -> ".mp3";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/ogg" -> ".ogg";
            case "application/pdf" -> ".pdf";
            case "application/json" -> ".json";
            case "application/zip" -> ".zip";
            default -> ".bin";
        };
    }

    private String artifactType(String contentType) {
        return switch (contentType) {
            case "image" -> "image";
            case "audio" -> "audio";
            default -> "file";
        };
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String safeReason(RuntimeException exception) {
        return limit(StringUtils.defaultIfBlank(exception.getMessage(), exception.getClass().getSimpleName()), 300);
    }

    private String limit(String value, int limit) {
        String safe = StringUtils.defaultString(value);
        return safe.length() <= limit ? safe : safe.substring(0, limit) + "...(truncated,total=" + safe.length() + ")";
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || StringUtils.isNotBlank(text))) target.put(key, value);
    }

    private static final class MappingState {
        private final List<String> observationParts = new ArrayList<>();
        private final List<Map<String, Object>> contentSummaries = new ArrayList<>();
        private final List<String> artifactRefs = new ArrayList<>();
        private final List<Map<String, Object>> artifactDescriptors = new ArrayList<>();
        private final List<String> binaryErrors = new ArrayList<>();
        private final Map<String, Object> structuredOutput = new LinkedHashMap<>();
        private int totalBinaryBytes;
    }
}
