package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationRequest;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationResult;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationService;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.ledger.adapter.repository.IImageGenerationExecutionRepository;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionInputArtifactEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionSource;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressCommand;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将 Image 私有稳定 SSE 收口为运行事实、Artifact 登记和专属执行投影。 */
@Service
@RequiredArgsConstructor
public class ImageGenerationRuntimeFactService {

    private static final String EVENT_SCHEMA = "image_generation_progress_event.v1";
    private static final Set<String> PHASES = Set.of(
            "inputs_resolved", "generation_started", "artifact_created", "completed", "failed"
    );
    private static final Set<String> STATUSES = Set.of("running", "completed", "failed");
    private static final int SUMMARY_LIMIT = 1200;

    private final IImageGenerationExecutionRepository executionRepository;
    private final ArtifactRuntimeRegistrationService artifactRegistrationService;
    private final RichRuntimeProgressRecordingService richProgressRecordingService;

    @Transactional
    public void start(ToolInvocationLedgerEntity invocation, ImageGenerationStartRequest request) {
        Instant now = Instant.now();
        ImageGenerationExecutionEntity execution = ImageGenerationExecutionEntity.builder()
                .imageGenerationExecutionId(UUID.randomUUID().toString())
                .executionSource(ImageGenerationExecutionSource.AGENT_RUNTIME)
                .toolInvocationId(invocation.getToolInvocationId())
                .runId(invocation.getRunId())
                .sessionId(invocation.getSessionId())
                .requestId(invocation.getRequestId())
                .generationMode(StringUtils.defaultIfBlank(request.getMode(), request.getReferenceArtifacts().isEmpty() ? "images" : "edits"))
                .requestedSize(request.getSize())
                .requestedOutputCount(request.getRequestedCount())
                .generatedOutputCount(0)
                .referenceArtifactCount(request.getReferenceArtifacts().size())
                .maskArtifactCount(request.getMaskArtifacts().size())
                .usedFallback(false)
                .status("running")
                .startedAt(now)
                .versionNo(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        executionRepository.save(execution);
        executionRepository.saveInputArtifacts(inputRelations(execution.getImageGenerationExecutionId(), request, now));
    }

    @Transactional
    public EventHandlingResult accept(
            ToolInvocationLedgerEntity invocation,
            AgentContext context,
            ImageGenerationStreamEvent event
    ) {
        validate(event, invocation);
        if (richProgressRecordingService.isDuplicate(invocation.getToolInvocationId(), event.getEventId())) {
            return EventHandlingResult.duplicateResult();
        }
        ImageGenerationExecutionEntity execution = loadExecution(invocation);
        bindPythonExecutionId(execution, event);
        ArtifactRuntimeRegistrationResult registration = registerArtifactIfPresent(invocation, context, event);
        String artifactId = artifactId(registration);
        RichRuntimeProgressCommand command = RichRuntimeProgressCommand.builder()
                .invocation(invocation)
                .agentId(agentId(context))
                .upstreamEventId(event.getEventId())
                .sequenceNo(event.getSequence())
                .eventType("image_generation_stage")
                .stage(event.getPhase())
                .status(event.getStatus())
                .occurredAt(event.getOccurredAt())
                .message(limit(event.getSummary(), SUMMARY_LIMIT))
                .presentationKind("image.progress.v1")
                .presentationSchemaVersion("1")
                .presentationData(presentation(event, artifactId))
                .artifactId(artifactId)
                .errorCode(errorCode(event))
                .errorMessage("failed".equals(event.getStatus()) ? limit(event.getSummary(), SUMMARY_LIMIT) : null)
                .build();
        RuntimeEventLedgerEntity fact = richProgressRecordingService.record(command).getFact();
        updateExecution(execution, event, artifactId);
        artifactRegistrationService.publishCreatedAfterCommit(registration, context);
        return EventHandlingResult.persistedResult(fact, artifactId);
    }

    @Transactional
    public void finish(
            ToolInvocationLedgerEntity invocation,
            ImageGenerationFinalResult result,
            boolean success,
            String errorCode,
            String errorMessage
    ) {
        ImageGenerationExecutionEntity execution = executionRepository.findByToolInvocationId(invocation.getToolInvocationId())
                .orElse(null);
        if (execution == null) return;
        Instant now = Instant.now();
        execution.setStatus(success ? "completed" : "failed");
        execution.setFinalSummary(limit(result == null ? null : result.getSummary(), SUMMARY_LIMIT));
        execution.setUsedFallback(success && result != null && Boolean.TRUE.equals(result.getUsedFallback()));
        execution.setErrorCode(success ? null : firstNonBlank(errorCode, result == null ? null : result.getErrorCode()));
        execution.setErrorMessage(success ? null : limit(errorMessage, SUMMARY_LIMIT));
        execution.setFinishedAt(now);
        execution.setUpdatedAt(now);
        execution.setVersionNo(nextVersion(execution));
        executionRepository.update(execution);
    }

    private List<ImageGenerationExecutionInputArtifactEntity> inputRelations(
            String executionId,
            ImageGenerationStartRequest request,
            Instant createdAt
    ) {
        List<ImageGenerationExecutionInputArtifactEntity> inputs = new ArrayList<>();
        appendInputRelations(inputs, executionId, request.getReferenceArtifacts(), "reference", createdAt);
        appendInputRelations(inputs, executionId, request.getMaskArtifacts(), "mask", createdAt);
        return inputs;
    }

    private void appendInputRelations(
            List<ImageGenerationExecutionInputArtifactEntity> target,
            String executionId,
            List<ImageGenerationInputArtifactDescriptor> artifacts,
            String role,
            Instant createdAt
    ) {
        for (int index = 0; index < artifacts.size(); index++) {
            target.add(ImageGenerationExecutionInputArtifactEntity.builder()
                    .imageGenerationExecutionId(executionId)
                    .artifactId(artifacts.get(index).getArtifactId())
                    .inputRole(role)
                    .inputOrder(index)
                    .createdAt(createdAt)
                    .build());
        }
    }

    private void validate(ImageGenerationStreamEvent event, ToolInvocationLedgerEntity invocation) {
        boolean invalid = event == null || invocation == null || !EVENT_SCHEMA.equals(event.getSchemaVersion())
                || StringUtils.isAnyBlank(
                invocation.getToolInvocationId(), event.getEventId(), event.getExecutionId(), event.getPhase(), event.getStatus(), event.getSummary()
        ) || event.getSequence() == null || event.getSequence() < 1 || event.getOccurredAt() == null
                || !PHASES.contains(event.getPhase()) || !STATUSES.contains(event.getStatus())
                || event.getSummary().length() > SUMMARY_LIMIT || !validPhaseStatus(event);
        if (invalid) {
            throw new ImageGenerationStreamException("image_generation_event_invalid", "图片生成上游稳定阶段不符合受控协议");
        }
    }

    private boolean validPhaseStatus(ImageGenerationStreamEvent event) {
        if ("completed".equals(event.getPhase())) return "completed".equals(event.getStatus());
        if ("failed".equals(event.getPhase())) return "failed".equals(event.getStatus());
        return "running".equals(event.getStatus());
    }

    private ImageGenerationExecutionEntity loadExecution(ToolInvocationLedgerEntity invocation) {
        return executionRepository.findByToolInvocationId(invocation.getToolInvocationId()).orElseThrow(
                () -> new ImageGenerationStreamException("image_generation_execution_missing", "图片生成专属执行记录不存在")
        );
    }

    private void bindPythonExecutionId(ImageGenerationExecutionEntity execution, ImageGenerationStreamEvent event) {
        if (StringUtils.isBlank(execution.getPythonExecutionId())) {
            execution.setPythonExecutionId(event.getExecutionId());
            return;
        }
        if (!execution.getPythonExecutionId().equals(event.getExecutionId())) {
            throw new ImageGenerationStreamException("image_generation_execution_mismatch", "图片生成上游执行标识不一致");
        }
    }

    private ArtifactRuntimeRegistrationResult registerArtifactIfPresent(
            ToolInvocationLedgerEntity invocation,
            AgentContext context,
            ImageGenerationStreamEvent event
    ) {
        if (!"artifact_created".equals(event.getPhase())) return null;
        Object raw = event.getData().get("artifact");
        if (!(raw instanceof Map<?, ?> source)) {
            throw new ImageGenerationStreamException("image_generation_artifact_invalid", "图片生成 artifact_created 阶段缺少 Artifact 描述符");
        }
        ArtifactRuntimeDescriptor descriptor = artifactDescriptor(source);
        if (StringUtils.isAnyBlank(
                descriptor.getArtifactId(), descriptor.getName(), descriptor.getArtifactType(), descriptor.getArtifactRole(),
                descriptor.getVisibility(), descriptor.getSourceType(), descriptor.getSourceName(), descriptor.getFileName(),
                descriptor.getStorageType(), descriptor.getStorageKey(), descriptor.getStorageRef(), descriptor.getContentSummary(),
                descriptor.getMimeType(), descriptor.getFileHash()
        ) || descriptor.getFileSize() == null || descriptor.getFileSize() < 0 || !descriptor.hasStableLocation()
                || !"image".equals(descriptor.getArtifactType()) || !"output".equals(descriptor.getArtifactRole())
                || !"visible".equals(descriptor.getVisibility())) {
            throw new ImageGenerationStreamException("image_generation_artifact_invalid", "图片生成 Artifact 描述符不完整");
        }
        ArtifactRuntimeSource sourceInfo = ArtifactRuntimeSource.builder()
                .runId(invocation.getRunId()).sessionId(invocation.getSessionId()).requestId(invocation.getRequestId())
                .traceId(invocation.getTraceId()).step(invocation.getStepNo()).toolCallId(invocation.getToolCallId())
                .toolName(invocation.getToolName()).build();
        return artifactRegistrationService.register(ArtifactRuntimeRegistrationRequest.builder()
                .context(context).toolInvocation(invocation).source(sourceInfo).descriptor(descriptor).build());
    }

    private ArtifactRuntimeDescriptor artifactDescriptor(Map<?, ?> source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source.get("metadata") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> metadata.put(String.valueOf(key), value));
        }
        return ArtifactRuntimeDescriptor.builder()
                .artifactId(value(source, "artifactId")).name(value(source, "name")).artifactType(value(source, "artifactType"))
                .artifactRole(value(source, "artifactRole")).visibility(value(source, "visibility"))
                .sourceType(value(source, "sourceType")).sourceId(value(source, "artifactId")).sourceName(value(source, "sourceName"))
                .fileName(value(source, "fileName")).storageType(value(source, "storageType")).storageKey(value(source, "storageKey"))
                .storageRef(value(source, "storageRef")).contentSummary(limit(value(source, "contentSummary"), SUMMARY_LIMIT))
                .mimeType(value(source, "mimeType")).fileSize(longValue(source.get("fileSize"))).fileHash(value(source, "fileHash"))
                .metadata(metadata).build();
    }

    /** 仅传递前端可展示的稳定字段；descriptor、locator、上游 URL/Base64 均不进入事实 payload。 */
    private Map<String, Object> presentation(ImageGenerationStreamEvent event, String artifactId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", event.getPhase());
        data.put("status", event.getStatus());
        copyText(event.getData(), data, "mode", 32);
        copyText(event.getData(), data, "size", 32);
        copyText(event.getData(), data, "fileName", 255);
        copyNonNegativeNumber(event.getData(), data, "referenceCount");
        copyNonNegativeNumber(event.getData(), data, "maskCount");
        copyNonNegativeNumber(event.getData(), data, "requestedCount");
        copyNonNegativeNumber(event.getData(), data, "generatedCount");
        copyNonNegativeNumber(event.getData(), data, "outputIndex");
        if (event.getData().get("usedFallback") instanceof Boolean value) data.put("usedFallback", value);
        if (StringUtils.isNotBlank(artifactId)) data.put("artifactId", artifactId);
        return data;
    }

    private void updateExecution(ImageGenerationExecutionEntity execution, ImageGenerationStreamEvent event, String artifactId) {
        Instant now = Instant.now();
        execution.setStatus(event.getStatus());
        execution.setUpdatedAt(now);
        execution.setVersionNo(nextVersion(execution));
        if (StringUtils.isNotBlank(artifactId)) {
            execution.setGeneratedOutputCount((execution.getGeneratedOutputCount() == null ? 0 : execution.getGeneratedOutputCount()) + 1);
        }
        if (event.getData().get("usedFallback") instanceof Boolean usedFallback) execution.setUsedFallback(usedFallback);
        if ("completed".equals(event.getPhase()) || "failed".equals(event.getPhase())) {
            execution.setFinishedAt(now);
            execution.setFinalSummary(limit(event.getSummary(), SUMMARY_LIMIT));
            if ("failed".equals(event.getStatus())) {
                execution.setErrorCode(errorCode(event));
                execution.setErrorMessage(limit(event.getSummary(), SUMMARY_LIMIT));
            }
        }
        executionRepository.update(execution);
    }

    private String errorCode(ImageGenerationStreamEvent event) {
        Object value = event.getData().get("errorCode");
        return value == null ? ("failed".equals(event.getStatus()) ? "image_generation_failed" : null) : limit(String.valueOf(value), 120);
    }

    private String artifactId(ArtifactRuntimeRegistrationResult registration) {
        return registration == null || registration.getArtifact() == null ? null : registration.getArtifact().getArtifactId();
    }

    private String agentId(AgentContext context) {
        Object value = context == null ? null : context.getAttribute("agentId");
        return value == null ? null : String.valueOf(value);
    }

    private long nextVersion(ImageGenerationExecutionEntity entity) {
        return (entity.getVersionNo() == null ? 0L : entity.getVersionNo()) + 1L;
    }

    private String value(Map<?, ?> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        try {
            return value == null ? null : value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void copyText(Map<String, Object> source, Map<String, Object> target, String key, int maxLength) {
        Object value = source.get(key);
        if (value != null && StringUtils.isNotBlank(String.valueOf(value))) target.put(key, limit(String.valueOf(value), maxLength));
    }

    private void copyNonNegativeNumber(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof Number number && number.intValue() >= 0) target.put(key, number.intValue());
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Tool 侧区分幂等重传与已提交的新运行事实。 */
    public record EventHandlingResult(boolean duplicate, RuntimeEventLedgerEntity fact, String artifactId) {
        public static EventHandlingResult duplicateResult() { return new EventHandlingResult(true, null, null); }
        public static EventHandlingResult persistedResult(RuntimeEventLedgerEntity fact, String artifactId) {
            return new EventHandlingResult(false, fact, artifactId);
        }
    }
}
