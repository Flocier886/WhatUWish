package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationRequest;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationResult;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationService;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.ledger.adapter.repository.IReportExecutionRepository;
import com.flocier.domain.ledger.model.entity.ReportExecutionEntity;
import com.flocier.domain.ledger.model.entity.ReportExecutionInputArtifactEntity;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressCommand;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Report 专属 SSE 到通用运行事实的适配器。
 *
 * <p>稳定阶段由 Rich Runtime 先落账、提交后投影；正式文件由 Artifact Runtime 登记。
 * 本类只拥有 Report 的专属执行状态，不直接写通用事件/Artifact Ledger 或直接发布 Artifact 事件。</p>
 */
@Service
@RequiredArgsConstructor
public class ReportRuntimeFactService {
    private static final String EVENT_SCHEMA = "report_progress_event.v1";
    private static final Set<String> PHASES = Set.of("inputs_resolved", "generation_started", "artifact_created", "completed", "failed");
    private static final Set<String> STATUSES = Set.of("running", "completed", "failed");
    private static final int SUMMARY_LIMIT = 1200;

    private final IReportExecutionRepository executionRepository;
    private final ArtifactRuntimeRegistrationService artifactRegistrationService;
    private final RichRuntimeProgressRecordingService richProgressRecordingService;

    @Transactional
    public void start(ToolInvocationLedgerEntity invocation, ReportStartRequest request) {
        Instant now = Instant.now();
        ReportExecutionEntity execution = ReportExecutionEntity.builder()
                .reportExecutionId(UUID.randomUUID().toString())
                .toolInvocationId(invocation.getToolInvocationId())
                .runId(invocation.getRunId())
                .sessionId(invocation.getSessionId())
                .requestId(invocation.getRequestId())
                .taskSummary(limit(request.getTask(), 600))
                .outputType(request.getOutputType())
                .outputFileName(request.getOutputFileName())
                .status("running")
                .inputArtifactCount(request.getInputArtifacts().size())
                .startedAt(now)
                .versionNo(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        executionRepository.save(execution);
        List<ReportExecutionInputArtifactEntity> inputs = new java.util.ArrayList<>();
        for (int index = 0; index < request.getInputArtifacts().size(); index++) {
            ReportInputArtifactDescriptor item = request.getInputArtifacts().get(index);
            inputs.add(ReportExecutionInputArtifactEntity.builder()
                        .reportExecutionId(execution.getReportExecutionId())
                        .artifactId(item.getArtifactId())
                        .inputOrder(index)
                        .createdAt(now)
                        .build());
        }
        executionRepository.saveInputArtifacts(inputs);
    }

    @Transactional
    public EventHandlingResult accept(ToolInvocationLedgerEntity invocation, AgentContext context, ReportStreamEvent event) {
        validate(event, invocation);
        if (richProgressRecordingService.isDuplicate(invocation.getToolInvocationId(), event.getEventId())) {
            return EventHandlingResult.duplicateResult();
        }
        ReportExecutionEntity execution = loadExecution(invocation);
        bindPythonExecutionId(execution, event);
        ArtifactRuntimeRegistrationResult artifactRegistration = registerArtifactIfPresent(invocation, context, event);
        RichRuntimeProgressCommand command = RichRuntimeProgressCommand.builder()
                .invocation(invocation)
                .agentId(agentId(context))
                .upstreamEventId(event.getEventId())
                .sequenceNo(event.getSequence())
                .eventType("report_stage")
                .stage(event.getPhase())
                .status(event.getStatus())
                .occurredAt(event.getOccurredAt())
                .message(limit(event.getSummary(), SUMMARY_LIMIT))
                .presentationKind("report.progress.v1")
                .presentationSchemaVersion("1")
                .presentationData(presentation(event, artifactId(artifactRegistration)))
                .artifactId(artifactId(artifactRegistration))
                .errorCode(errorCode(event))
                .errorMessage("failed".equals(event.getStatus()) ? limit(event.getSummary(), SUMMARY_LIMIT) : null)
                .build();
        RuntimeEventLedgerEntity fact = richProgressRecordingService.record(command).getFact();
        updateExecution(execution, event, artifactId(artifactRegistration));
        artifactRegistrationService.publishCreatedAfterCommit(artifactRegistration, context);
        return EventHandlingResult.persistedResult(fact);
    }

    @Transactional
    public void finish(ToolInvocationLedgerEntity invocation, ReportFinalResult result,
                       boolean success, String errorCode, String errorMessage) {
        ReportExecutionEntity execution = executionRepository.findByToolInvocationId(invocation.getToolInvocationId()).orElse(null);
        if (execution == null) return;
        Instant now = Instant.now();
        execution.setStatus(success ? "completed" : "failed");
        execution.setFinalSummary(limit(result == null ? null : result.getSummary(), 1200));
        execution.setErrorCode(success ? null : firstNonBlank(errorCode, result == null ? null : result.getErrorCode()));
        execution.setErrorMessage(success ? null : limit(errorMessage, SUMMARY_LIMIT));
        execution.setFinishedAt(now);
        execution.setUpdatedAt(now);
        execution.setVersionNo(nextVersion(execution));
        executionRepository.update(execution);
    }

    private void validate(ReportStreamEvent event, ToolInvocationLedgerEntity invocation) {
        boolean invalid = event == null || invocation == null || !EVENT_SCHEMA.equals(event.getSchemaVersion())
                || StringUtils.isAnyBlank(invocation.getToolInvocationId(), event.getEventId(), event.getExecutionId(), event.getPhase(), event.getStatus(), event.getSummary())
                || event.getSequence() == null || event.getSequence() < 1 || event.getOccurredAt() == null
                || !PHASES.contains(event.getPhase()) || !STATUSES.contains(event.getStatus())
                || event.getSummary().length() > SUMMARY_LIMIT;
        if (invalid) throw new ReportStreamException("report_event_invalid", "Report 上游稳定阶段不符合受控协议");
    }

    private ReportExecutionEntity loadExecution(ToolInvocationLedgerEntity invocation) {
        return executionRepository.findByToolInvocationId(invocation.getToolInvocationId())
                .orElseThrow(() -> new ReportStreamException("report_execution_missing", "Report 专属执行记录不存在"));
    }

    private void bindPythonExecutionId(ReportExecutionEntity execution, ReportStreamEvent event) {
        if (StringUtils.isBlank(execution.getPythonExecutionId())) {
            execution.setPythonExecutionId(event.getExecutionId());
            return;
        }
        if (!execution.getPythonExecutionId().equals(event.getExecutionId())) {
            throw new ReportStreamException("report_execution_mismatch", "Report 上游执行标识不一致");
        }
    }

    private ArtifactRuntimeRegistrationResult registerArtifactIfPresent(
            ToolInvocationLedgerEntity invocation, AgentContext context, ReportStreamEvent event
    ) {
        if (!"artifact_created".equals(event.getPhase())) return null;
        Object raw = event.getData().get("artifact");
        if (!(raw instanceof Map<?, ?> source)) {
            throw new ReportStreamException("report_artifact_invalid", "Report artifact_created 阶段缺少 Artifact 描述符");
        }
        ArtifactRuntimeDescriptor descriptor = artifactDescriptor(source);
        if (StringUtils.isAnyBlank(descriptor.getArtifactId(), descriptor.getName(), descriptor.getArtifactType(),
                descriptor.getArtifactRole(), descriptor.getVisibility(), descriptor.getSourceType(), descriptor.getSourceName(),
                descriptor.getFileName(), descriptor.getStorageType(), descriptor.getContentSummary(), descriptor.getMimeType())
                || descriptor.getFileSize() == null || descriptor.getFileSize() < 0 || !descriptor.hasStableLocation()) {
            throw new ReportStreamException("report_artifact_invalid", "Report Artifact 描述符不完整");
        }
        ArtifactRuntimeSource sourceInfo = ArtifactRuntimeSource.builder()
                .runId(invocation.getRunId())
                .sessionId(invocation.getSessionId())
                .requestId(invocation.getRequestId())
                .traceId(invocation.getTraceId())
                .step(invocation.getStepNo())
                .toolCallId(invocation.getToolCallId())
                .toolName(invocation.getToolName())
                .build();
        return artifactRegistrationService.register(ArtifactRuntimeRegistrationRequest.builder()
                .context(context).toolInvocation(invocation).source(sourceInfo).descriptor(descriptor).build());
    }

    private ArtifactRuntimeDescriptor artifactDescriptor(Map<?, ?> source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source.get("metadata") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> metadata.put(String.valueOf(key), value));
        }
        return ArtifactRuntimeDescriptor.builder()
                .artifactId(value(source, "artifactId"))
                .name(value(source, "name"))
                .artifactType(value(source, "artifactType"))
                .artifactRole(value(source, "artifactRole"))
                .visibility(value(source, "visibility"))
                .sourceType(value(source, "sourceType"))
                .sourceId(value(source, "artifactId"))
                .sourceName(value(source, "sourceName"))
                .fileName(value(source, "fileName"))
                .storageType(value(source, "storageType"))
                .storageKey(value(source, "storageKey"))
                .storageRef(value(source, "storageRef"))
                .contentSummary(limit(value(source, "contentSummary"), SUMMARY_LIMIT))
                .mimeType(value(source, "mimeType"))
                .fileSize(longValue(source.get("fileSize")))
                .fileHash(value(source, "fileHash"))
                .metadata(metadata)
                .build();
    }

    private Map<String, Object> presentation(ReportStreamEvent event, String artifactId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", event.getPhase());
        data.put("status", event.getStatus());
        Object outputType = event.getData().get("outputType");
        if (outputType != null) data.put("outputType", String.valueOf(outputType));
        Object inputCount = event.getData().get("inputArtifactCount");
        if (inputCount instanceof Number) data.put("inputArtifactCount", ((Number) inputCount).intValue());
        Object acceptedCount = event.getData().get("acceptedArtifactCount");
        if (acceptedCount instanceof Number) data.put("acceptedArtifactCount", ((Number) acceptedCount).intValue());
        Object fileName = event.getData().get("fileName");
        if (fileName != null) data.put("fileName", limit(String.valueOf(fileName), 255));
        if (StringUtils.isNotBlank(artifactId)) data.put("artifactId", artifactId);
        return data;
    }

    private void updateExecution(ReportExecutionEntity execution, ReportStreamEvent event, String artifactId) {
        Instant now = Instant.now();
        execution.setStatus(event.getStatus());
        execution.setUpdatedAt(now);
        execution.setVersionNo(nextVersion(execution));
        if (StringUtils.isNotBlank(artifactId)) execution.setOutputArtifactId(artifactId);
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

    private String errorCode(ReportStreamEvent event) {
        Object value = event.getData().get("errorCode");
        return value == null ? ("failed".equals(event.getStatus()) ? "report_failed" : null) : limit(String.valueOf(value), 120);
    }

    private String artifactId(ArtifactRuntimeRegistrationResult registration) {
        return registration == null || registration.getArtifact() == null ? null : registration.getArtifact().getArtifactId();
    }

    private String agentId(AgentContext context) {
        Object value = context == null ? null : context.getAttribute("agentId");
        return value == null ? null : String.valueOf(value);
    }

    private long nextVersion(ReportExecutionEntity entity) {
        return (entity.getVersionNo() == null ? 0L : entity.getVersionNo()) + 1L;
    }

    private String value(Map<?, ?> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        try {
            return value == null
                    ? null
                    : value instanceof Number number
                    ? number.longValue()
                    : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String limit(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    /** 供 Tool 侧区分幂等上游重传和新事实。 */
    public static final class EventHandlingResult {
        private final boolean duplicate;
        private final RuntimeEventLedgerEntity fact;

        private EventHandlingResult(boolean duplicate, RuntimeEventLedgerEntity fact) {
            this.duplicate = duplicate;
            this.fact = fact;
        }

        public static EventHandlingResult duplicateResult() {
            return new EventHandlingResult(true, null);
        }

        public static EventHandlingResult persistedResult(RuntimeEventLedgerEntity fact) {
            return new EventHandlingResult(false, fact);
        }

        public boolean isDuplicate() {
            return duplicate;
        }

        public RuntimeEventLedgerEntity getFact() {
            return fact;
        }
    }
}
