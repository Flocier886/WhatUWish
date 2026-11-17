package com.flocier.domain.ledger.service.rich;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.event.ToolProgressPayload;
import com.flocier.domain.ledger.adapter.repository.IRuntimeEventLedgerRepository;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Rich Tool 的通用过程事实追加、顺序校验和提交后 Live 投影。 */
@Service
@RequiredArgsConstructor
public class RichRuntimeProgressRecordingService {

    private static final Set<String> FORBIDDEN_PRESENTATION_FIELDS = Set.of(
            "prompt", "rawbody", "rawresponse", "storagekey", "storageref", "filepath", "localpath", "content"
    );

    private final IRuntimeEventLedgerRepository runtimeEventLedgerRepository;
    private final RuntimeEventPublisher runtimeEventPublisher;

    public boolean isDuplicate(String toolInvocationId, String upstreamEventId) {
        return StringUtils.isNotBlank(toolInvocationId) && StringUtils.isNotBlank(upstreamEventId)
                && runtimeEventLedgerRepository
                .findByToolInvocationIdAndUpstreamEventId(toolInvocationId, upstreamEventId)
                .isPresent();
    }

    @Transactional
    public RichRuntimeProgressResult record(RichRuntimeProgressCommand command) {
        validate(command);
        ToolInvocationLedgerEntity invocation = command.getInvocation();
        if (isDuplicate(invocation.getToolInvocationId(), command.getUpstreamEventId())) {
            return RichRuntimeProgressResult.duplicateResult();
        }
        runtimeEventLedgerRepository
                .findByToolInvocationIdAndSequenceNo(invocation.getToolInvocationId(), command.getSequenceNo())
                .ifPresent(ignored -> {
                    throw new RichRuntimeProgressException(
                            "rich_runtime_sequence_conflict",
                            "Rich Tool 上游事件序号冲突"
                    );
                });
        List<RuntimeEventLedgerEntity> existing = runtimeEventLedgerRepository
                .findByToolInvocationIdOrderBySequenceNo(invocation.getToolInvocationId());
        int expected = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSequenceNo() + 1;
        if (command.getSequenceNo() != expected) {
            throw new RichRuntimeProgressException("rich_runtime_sequence_gap", "Rich Tool 上游事件顺序不连续");
        }
        Instant now = Instant.now();
        RuntimeEventLedgerEntity fact = RuntimeEventLedgerEntity.builder()
                .runtimeEventId(UUID.randomUUID().toString())
                .runId(invocation.getRunId())
                .sessionId(invocation.getSessionId())
                .requestId(invocation.getRequestId())
                .toolInvocationId(invocation.getToolInvocationId())
                .sequenceNo(command.getSequenceNo())
                .upstreamEventId(command.getUpstreamEventId())
                .eventType(command.getEventType())
                .stage(command.getStage())
                .status(command.getStatus())
                .occurredAt(command.getOccurredAt())
                .recordedAt(now)
                .message(command.getMessage())
                .presentationKind(command.getPresentationKind())
                .payloadSchemaVersion(command.getPresentationSchemaVersion())
                .payloadJson(JSON.toJSONString(command.getPresentationData()))
                .artifactId(command.getArtifactId())
                .errorCode(command.getErrorCode())
                .errorMessage(command.getErrorMessage())
                .createdAt(now)
                .updatedAt(now)
                .build();
        runtimeEventLedgerRepository.save(fact);
        runtimeEventPublisher.publishAfterCommit(event(invocation, command, fact));
        return new RichRuntimeProgressResult(fact, false);
    }

    private void validate(RichRuntimeProgressCommand command) {
        ToolInvocationLedgerEntity invocation = command == null ? null : command.getInvocation();
        boolean invalid = command == null
                || invocation == null
                || StringUtils.isAnyBlank(
                        invocation.getToolInvocationId(),
                        invocation.getRunId(),
                        command.getUpstreamEventId(),
                        command.getEventType(),
                        command.getStage(),
                        command.getStatus(),
                        command.getMessage(),
                        command.getPresentationKind(),
                        command.getPresentationSchemaVersion()
                )
                || command.getSequenceNo() == null
                || command.getSequenceNo() < 1
                || command.getOccurredAt() == null
                || containsForbiddenField(command.getPresentationData());
        if (invalid) {
            throw new RichRuntimeProgressException("rich_runtime_progress_invalid", "Rich Tool 过程事实不符合受控记录合同");
        }
    }

    private boolean containsForbiddenField(Object value) {
        if (value instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                String key = entry.getKey() == null
                        ? ""
                        : String.valueOf(entry.getKey()).replace("_", "").toLowerCase();
                if (FORBIDDEN_PRESENTATION_FIELDS.contains(key) || containsForbiddenField(entry.getValue())) {
                    return true;
                }
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (containsForbiddenField(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private RuntimeEventDraft event(ToolInvocationLedgerEntity invocation, RichRuntimeProgressCommand command,
                                    RuntimeEventLedgerEntity fact) {
        ToolProgressPayload payload = new ToolProgressPayload(
                fact.getRuntimeEventId(),
                invocation.getToolInvocationId(),
                invocation.getToolName(),
                command.getSequenceNo(),
                command.getStage(),
                command.getStatus(),
                command.getMessage(),
                command.getArtifactId(),
                command.getPresentationKind(),
                command.getPresentationSchemaVersion(),
                command.getPresentationData()
        );
        return RuntimeEventDraft.persistedToolProgress(
                fact.getRuntimeEventId(),
                command.getOccurredAt(),
                invocation.getSessionId(),
                invocation.getRunId(),
                invocation.getRequestId(),
                command.getAgentId(),
                payload
        );
    }
}
