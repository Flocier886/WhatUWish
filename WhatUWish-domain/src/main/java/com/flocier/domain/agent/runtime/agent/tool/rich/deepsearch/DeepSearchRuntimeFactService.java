package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationRequest;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationResult;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationService;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.ledger.adapter.repository.IDeepSearchExecutionRepository;
import com.flocier.domain.ledger.model.entity.DeepSearchExecutionEntity;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressCommand;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DeepSearch 专属 SSE 协议到运行事实账本的事务边界。
 *
 * 每个上游阶段事件在此处完成协议校验、幂等判断、artifact 登记、事实追加和执行投影更新。
 * 只有事务提交成功后，才发布使用同一 presentation 的 Runtime SSE。
 */
@Service
@RequiredArgsConstructor
public class DeepSearchRuntimeFactService {

    private static final String EVENT_SCHEMA = "deep_search_event.v1";
    private static final int SUMMARY_LIMIT = 1200;
    private static final int ERROR_LIMIT = 1200;

    private static final Set<String> PHASES = Set.of(
            "research_started",
            "plan_completed",
            "iteration_started",
            "search_started",
            "search_completed",
            "source_selection_completed",
            "fetch_started",
            "fetch_completed",
            "evidence_verified",
            "coverage_reviewed",
            "artifact_created",
            "research_completed",
            "research_failed",
            "research_cancelled"
    );

    private static final Set<String> STATUSES = Set.of(
            "running",
            "completed",
            "partial_success",
            "failed",
            "cancelled"
    );

    private final IDeepSearchExecutionRepository executionRepository;
    private final ArtifactRuntimeRegistrationService artifactRegistrationService;
    private final RichRuntimeProgressRecordingService richProgressRecordingService;
    private final DeepSearchPresentationMapper presentationMapper;

    @Transactional
    public void start(ToolInvocationLedgerEntity invocation, DeepSearchStartRequest request) {
        Instant now = Instant.now();
        String topic = stringValue(request.getPayload().get("topic"));
        String depth = StringUtils.defaultIfBlank(
                stringValue(request.getPayload().get("researchDepth")),
                "standard"
        );

        DeepSearchExecutionEntity entity = DeepSearchExecutionEntity.builder()
                .deepSearchExecutionId(UUID.randomUUID().toString())
                .toolInvocationId(invocation.getToolInvocationId())
                .runId(invocation.getRunId())
                .sessionId(invocation.getSessionId())
                .requestId(invocation.getRequestId())
                .queryText(topic)
                .researchMode(depth)
                .status("running")
                .lastSequenceNo(0)
                .versionNo(0L)
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        executionRepository.save(entity);
    }

    @Transactional
    public EventHandlingResult accept(
            ToolInvocationLedgerEntity invocation,
            AgentContext context,
            DeepSearchStreamEvent event
    ) {
        validate(event, invocation);
        if (richProgressRecordingService.isDuplicate(invocation.getToolInvocationId(), event.getEventId())) {
            return EventHandlingResult.duplicateResult();
        }

        DeepSearchExecutionEntity execution = loadExecution(invocation);
        validateSequence(execution, event);
        bindPythonExecutionId(execution, event);

        ArtifactRuntimeRegistrationResult artifactRegistration = registerArtifactIfPresent(invocation, context, event);
        DeepSearchPresentationMapper.DeepSearchPresentation presentation = presentationMapper.map(event);
        Instant now = Instant.now();
        RichRuntimeProgressCommand command = RichRuntimeProgressCommand.builder()
                .invocation(invocation)
                .agentId(agentId(context))
                .upstreamEventId(event.getEventId())
                .sequenceNo(event.getSequence())
                .eventType("deep_search_stage")
                .stage(event.getPhase())
                .status(event.getStatus())
                .occurredAt(event.getOccurredAt())
                .message(limit(event.getSummary(), SUMMARY_LIMIT))
                .presentationKind(presentation.kind())
                .presentationSchemaVersion(presentation.schemaVersion())
                .presentationData(presentation.data())
                .artifactId(artifactId(artifactRegistration))
                .errorCode(errorCode(event))
                .errorMessage(isFailed(event) ? limit(event.getSummary(), ERROR_LIMIT) : null)
                .build();
        RuntimeEventLedgerEntity fact = richProgressRecordingService.record(command).getFact();
        updateExecution(execution, event, artifactId(artifactRegistration), now);
        artifactRegistrationService.publishCreatedAfterCommit(artifactRegistration, context);
        return EventHandlingResult.persistedResult(fact);
    }

    @Transactional
    public void finish(
            ToolInvocationLedgerEntity invocation,
            DeepSearchFinalResult result,
            boolean success,
            String errorCode,
            String errorMessage
    ) {
        DeepSearchExecutionEntity execution = executionRepository
                .findByToolInvocationId(invocation.getToolInvocationId())
                .orElse(null);
        if (execution == null) {
            return;
        }

        Instant now = Instant.now();
        execution.setStatus(success
                ? StringUtils.defaultIfBlank(result == null ? null : result.getDeliveryDisposition(), "terminal_completed")
                : "failed");
        execution.setFinalSummary(limit(result == null ? null : result.getSummary(), 1800));
        execution.setErrorCode(success ? null : errorCode);
        execution.setErrorMessage(success ? null : limit(errorMessage, ERROR_LIMIT));
        execution.setFinishedAt(now);
        execution.setUpdatedAt(now);
        execution.setVersionNo(nextVersion(execution));
        executionRepository.update(execution);
    }


    private DeepSearchExecutionEntity loadExecution(ToolInvocationLedgerEntity invocation) {
        return executionRepository
                .findByToolInvocationId(invocation.getToolInvocationId())
                .orElseThrow(() -> new DeepSearchStreamException(
                        "deep_search_execution_missing",
                        "DeepSearch 专属执行记录不存在"
                ));
    }

    private void validateSequence(DeepSearchExecutionEntity execution, DeepSearchStreamEvent event) {
        int expectedSequence = execution.getLastSequenceNo() == null
                ? 1
                : execution.getLastSequenceNo() + 1;
        if (event.getSequence() != expectedSequence) {
            throw new DeepSearchStreamException(
                    "deep_search_event_sequence_gap",
                    "DeepSearch 上游事件顺序不连续"
            );
        }
    }

    private void bindPythonExecutionId(DeepSearchExecutionEntity execution, DeepSearchStreamEvent event) {
        if (StringUtils.isBlank(execution.getPythonExecutionId())) {
            execution.setPythonExecutionId(event.getExecutionId());
            return;
        }
        if (!execution.getPythonExecutionId().equals(event.getExecutionId())) {
            throw new DeepSearchStreamException(
                    "deep_search_execution_mismatch",
                    "DeepSearch 上游执行标识不一致"
            );
        }
    }

    private void updateExecution(
            DeepSearchExecutionEntity execution,
            DeepSearchStreamEvent event,
            String artifactId,
            Instant now
    ) {
        execution.setLastSequenceNo(event.getSequence());
        execution.setLastUpstreamEventId(event.getEventId());
        execution.setSchemaVersion(event.getSchemaVersion());
        execution.setStatus(event.getStatus());
        execution.setUpdatedAt(now);
        execution.setVersionNo(nextVersion(execution));
        if (StringUtils.isNotBlank(artifactId)) {
            execution.setReportArtifactId(artifactId);
        }
        if (isTerminal(event)) {
            execution.setFinishedAt(now);
            if (isFailed(event)) {
                execution.setErrorCode(errorCode(event));
                execution.setErrorMessage(limit(event.getSummary(), ERROR_LIMIT));
            }
        }
        executionRepository.update(execution);
    }

    private void validate(DeepSearchStreamEvent event, ToolInvocationLedgerEntity invocation) {
        boolean invalid = event == null
                || !EVENT_SCHEMA.equals(event.getSchemaVersion())
                || StringUtils.isBlank(event.getEventId())
                || StringUtils.isBlank(event.getExecutionId())
                || event.getSequence() == null
                || event.getSequence() < 1
                || !PHASES.contains(event.getPhase())
                || !STATUSES.contains(event.getStatus())
                || event.getOccurredAt() == null
                || StringUtils.isBlank(event.getSummary())
                || event.getSummary().length() > SUMMARY_LIMIT
                || invocation == null
                || StringUtils.isBlank(invocation.getToolInvocationId());
        if (invalid) {
            throw new DeepSearchStreamException(
                    "deep_search_event_invalid",
                    "DeepSearch 上游阶段事件不符合受控协议"
            );
        }
    }

    private ArtifactRuntimeRegistrationResult registerArtifactIfPresent(
            ToolInvocationLedgerEntity invocation,
            AgentContext context,
            DeepSearchStreamEvent event
    ) {
        if (!"artifact_created".equals(event.getPhase())) {
            return null;
        }

        Object rawArtifact = event.getData() == null ? null : event.getData().get("artifact");
        if (!(rawArtifact instanceof Map<?, ?> rawDescriptor)) {
            throw new DeepSearchStreamException(
                    "deep_search_artifact_invalid",
                    "DeepSearch artifact 事件缺少受控产物描述符"
            );
        }

        ArtifactRuntimeDescriptor descriptor = artifactDescriptor(rawDescriptor);
        if (StringUtils.isAnyBlank(
                descriptor.getArtifactId(),
                descriptor.getName(),
                descriptor.getArtifactType(),
                descriptor.getArtifactRole(),
                descriptor.getVisibility(),
                descriptor.getSourceType(),
                descriptor.getSourceName(),
                descriptor.getFileName(),
                descriptor.getStorageType(),
                descriptor.getContentSummary(),
                descriptor.getMimeType()
        ) || descriptor.getFileSize() == null
                || descriptor.getFileSize() < 0
                || !descriptor.hasStableLocation()) {
            throw new DeepSearchStreamException(
                    "deep_search_artifact_invalid",
                    "DeepSearch artifact 描述符不完整"
            );
        }
        ArtifactRuntimeSource source = ArtifactRuntimeSource.builder()
                .runId(invocation.getRunId())
                .sessionId(invocation.getSessionId())
                .requestId(invocation.getRequestId())
                .traceId(invocation.getTraceId())
                .step(invocation.getStepNo())
                .toolCallId(invocation.getToolCallId())
                .toolName(invocation.getToolName())
                .build();
        ArtifactRuntimeRegistrationRequest request = ArtifactRuntimeRegistrationRequest.builder()
                .context(context)
                .toolInvocation(invocation)
                .source(source)
                .descriptor(descriptor)
                .build();
        return artifactRegistrationService.register(request);
    }

    /** 将跨语言 Map 收口为项目内正式 Artifact 模型；解析完成后不保留第二套 DTO。 */
    private ArtifactRuntimeDescriptor artifactDescriptor(Map<?, ?> source) {
        Object metadata = source.get("metadata");
        Map<String, Object> copiedMetadata = new java.util.LinkedHashMap<>();
        if (metadata instanceof Map<?, ?> values) {
            values.forEach((key, item) -> copiedMetadata.put(String.valueOf(key), item));
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
                .metadata(copiedMetadata)
                .build();
    }

    private String artifactId(ArtifactRuntimeRegistrationResult registration) {
        if (registration == null || registration.getArtifact() == null) {
            return null;
        }
        return registration.getArtifact().getArtifactId();
    }

    private boolean isTerminal(DeepSearchStreamEvent event) {
        return "research_completed".equals(event.getPhase())
                || "research_failed".equals(event.getPhase())
                || "research_cancelled".equals(event.getPhase());
    }

    private boolean isFailed(DeepSearchStreamEvent event) {
        return "failed".equals(event.getStatus()) || "cancelled".equals(event.getStatus());
    }

    private String errorCode(DeepSearchStreamEvent event) {
        Object errorCode = event.getData() == null ? null : event.getData().get("errorCode");
        if (errorCode != null) {
            return limit(String.valueOf(errorCode), 120);
        }
        return isFailed(event) ? "deep_search_failed" : null;
    }

    private long nextVersion(DeepSearchExecutionEntity execution) {
        return (execution.getVersionNo() == null ? 0L : execution.getVersionNo()) + 1L;
    }

    private String agentId(AgentContext context) {
        Object agentId = context == null ? null : context.getAttribute("agentId");
        return agentId == null ? null : String.valueOf(agentId);
    }

    private String value(Map<?, ?> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record EventHandlingResult(boolean duplicate, RuntimeEventLedgerEntity fact) {

        public static EventHandlingResult duplicateResult() {
            return new EventHandlingResult(true, null);
        }

        public static EventHandlingResult persistedResult(RuntimeEventLedgerEntity fact) {
            return new EventHandlingResult(false, fact);
        }
    }
}
