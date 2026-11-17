package com.flocier.domain.agent.runtime.artifact;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将已登记的 runtime binding 写入 artifact ledger，绝不重新解析工具 payload。 */
@Slf4j
@Component
public class ArtifactRuntimeLedgerRecorder {
    private final LedgerRecordingService ledgerRecordingService;
    private final RuntimeEventMapper runtimeEventMapper;
    private final RuntimeEventPublisher runtimeEventPublisher;

    /** 保持已有直接构造测试和旧调用的兼容性。 */
    public ArtifactRuntimeLedgerRecorder(LedgerRecordingService ledgerRecordingService) {
        this(ledgerRecordingService, new RuntimeEventMapper(), RuntimeEventPublisher.noop());
    }

    @Autowired
    public ArtifactRuntimeLedgerRecorder(LedgerRecordingService ledgerRecordingService,
                                         RuntimeEventMapper runtimeEventMapper,
                                         RuntimeEventPublisher runtimeEventPublisher) {
        this.ledgerRecordingService = ledgerRecordingService;
        this.runtimeEventMapper = runtimeEventMapper;
        this.runtimeEventPublisher = runtimeEventPublisher;
    }

    public List<ArtifactLedgerEntity> recordToolArtifacts(AgentContext context,
                                                           ToolInvokeRequest request,
                                                           ToolExecutionResult result,
                                                           ToolInvocationLedgerEntity toolInvocation) {
        if (context == null || toolInvocation == null || ledgerRecordingService == null) {
            return List.of();
        }
        java.util.ArrayList<ArtifactLedgerEntity> recorded = new java.util.ArrayList<>();
        try {
            List<ArtifactRuntimeBinding> bindings = context.requireArtifactRuntimeRegistry()
                    .findByToolCallId(toolInvocation.getToolCallId());
            for (ArtifactRuntimeBinding binding : bindings) {
                ArtifactLedgerEntity entity = recordOne(toolInvocation, result, binding);
                if (entity != null) {
                    recorded.add(entity);
                    publishSafely(entity, context);
                }
            }
        } catch (Exception e) {
            log.warn("runtime artifact record skipped, toolInvocationId={}, toolCallId={}, reason={}",
                    toolInvocation.getToolInvocationId(), toolInvocation.getToolCallId(), e.getMessage(), e);
        }
        return List.copyOf(recorded);
    }

    private ArtifactLedgerEntity recordOne(ToolInvocationLedgerEntity toolInvocation,
                                           ToolExecutionResult result,
                                           ArtifactRuntimeBinding binding) {
        try {
            if (binding == null || binding.getDescriptor() == null || binding.getSource() == null) {
                return null;
            }
            ArtifactRuntimeDescriptor descriptor = binding.getDescriptor();
            ArtifactRuntimeSource source = binding.getSource();
            Instant now = Instant.now();
            String artifactId = StringUtils.defaultIfBlank(descriptor.getArtifactId(), UUID.randomUUID().toString());
            ArtifactLedgerEntity entity = ArtifactLedgerEntity.builder()
                    .artifactId(artifactId)
                    .runId(firstNonBlank(toolInvocation.getRunId(), source.getRunId()))
                    .sessionId(firstNonBlank(toolInvocation.getSessionId(), source.getSessionId()))
                    .requestId(firstNonBlank(toolInvocation.getRequestId(), source.getRequestId()))
                    .traceId(firstNonBlank(toolInvocation.getTraceId(), source.getTraceId()))
                    .toolInvocationId(toolInvocation.getToolInvocationId())
                    .toolCallId(firstNonBlank(toolInvocation.getToolCallId(), source.getToolCallId()))
                    .artifactRole(firstNonBlank(descriptor.getArtifactRole(), "output"))
                    .visibility(firstNonBlank(descriptor.getVisibility(), "visible"))
                    .sourceType(firstNonBlank(descriptor.getSourceType(), "tool_output"))
                    .sourceId(firstNonBlank(descriptor.getSourceId(), toolInvocation.getToolInvocationId()))
                    .sourceName(firstNonBlank(descriptor.getSourceName(), source.getToolName(), toolInvocation.getToolName()))
                    .artifactType(firstNonBlank(descriptor.getArtifactType(), "file"))
                    .artifactName(firstNonBlank(descriptor.getName(), descriptor.getFileName()))
                    .fileName(firstNonBlank(descriptor.getFileName(), descriptor.getName()))
                    .storageType(firstNonBlank(descriptor.getStorageType(), "file_ref"))
                    .storageRef(descriptor.getStorageRef())
                    .storageKey(descriptor.getStorageKey())
                    .downloadUrl(descriptor.getDownloadUrl())
                    .previewUrl(descriptor.getPreviewUrl())
                    .contentSummary(descriptor.getContentSummary())
                    .mimeType(descriptor.getMimeType())
                    .size(descriptor.getFileSize())
                    .fileSize(descriptor.getFileSize())
                    .fileHash(descriptor.getFileHash())
                    .metadataJson(metadataJson(descriptor.getMetadata()))
                    .deleted(0)
                    .idempotencyKey(idempotencyKey(source, descriptor))
                    .createdAt(now)
                    .updatedAt(now)
                    .ext(buildExt(binding, result))
                    .build();
            return ledgerRecordingService.recordArtifact(entity);
        } catch (Exception e) {
            log.warn("runtime artifact record one skipped, toolInvocationId={}, artifactKey={}, reason={}",
                    toolInvocation.getToolInvocationId(), binding == null ? null : binding.artifactKey(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 供运行中的 Rich Tool 登记入口复用已有 Artifact Ledger 映射和准入语义。
     * 调用方负责当前 Run Registry 绑定以及仅在首次登记后发布用户事件。
     */
    ArtifactLedgerEntity recordBinding(ToolInvocationLedgerEntity toolInvocation,
                                       ToolExecutionResult result,
                                       ArtifactRuntimeBinding binding) {
        return recordOne(toolInvocation, result, binding);
    }

    String idempotencyKeyFor(ArtifactRuntimeSource source, ArtifactRuntimeDescriptor descriptor) {
        return idempotencyKey(source, descriptor);
    }

    private void publishSafely(ArtifactLedgerEntity entity, AgentContext context) {
        String agentId = context == null ? null : context.getAttribute("agentId");
        try {
            runtimeEventPublisher.publish(runtimeEventMapper.artifactCreated(entity, agentId));
        } catch (Exception e) {
            log.warn("runtime artifact event publish skipped, artifactId={}, reason={}", entity.getArtifactId(), e.getMessage(), e);
        }
    }

    private String idempotencyKey(ArtifactRuntimeSource source, ArtifactRuntimeDescriptor descriptor) {
        String rawKey = String.join(":", "artifact", "tool", StringUtils.defaultString(source.getRunId(), source.getRequestId()),
                StringUtils.defaultString(source.getToolCallId()), StringUtils.defaultString(descriptor.getArtifactId()),
                StringUtils.defaultString(descriptor.stableRef()));
        // storageKey 可达数百字符，只能参与判重计算，不能直接写入有限长度的数据库键字段。
        return "artifact:" + sha256(rawKey).substring(0, 48);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private Map<String, Object> buildExt(ArtifactRuntimeBinding binding, ToolExecutionResult result) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("runtimeRegistry", "ArtifactRuntimeRegistry");
        ext.put("artifactKey", binding.artifactKey());
        ext.put("toolName", result == null ? null : result.getToolName());
        ext.put("metadata", binding.getDescriptor().getMetadata());
        return ext;
    }

    private String metadataJson(Map<String, Object> metadata) {
        return metadata == null || metadata.isEmpty() ? null : JSON.toJSONString(metadata);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
