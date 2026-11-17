package com.flocier.infrastructure.adapter.repository;

import com.flocier.domain.ledger.adapter.repository.IRuntimeEventLedgerRepository;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.infrastructure.dao.IRuntimeEventLedgerDao;
import com.flocier.infrastructure.dao.po.RuntimeEventLedgerPO;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "mysql")
public class MysqlRuntimeEventLedgerRepository implements IRuntimeEventLedgerRepository {

    @Resource
    private IRuntimeEventLedgerDao dao;

    @Override
    public void save(RuntimeEventLedgerEntity entity) {
        dao.insert(toPo(entity));
    }

    @Override
    public Optional<RuntimeEventLedgerEntity> findByToolInvocationIdAndUpstreamEventId(
            String toolInvocationId,
            String upstreamEventId
    ) {
        return Optional.ofNullable(dao.queryByInvocationAndUpstreamEvent(toolInvocationId, upstreamEventId))
                .map(this::toEntity);
    }

    @Override
    public Optional<RuntimeEventLedgerEntity> findByToolInvocationIdAndSequenceNo(
            String toolInvocationId,
            Integer sequenceNo
    ) {
        return Optional.ofNullable(dao.queryByInvocationAndSequence(toolInvocationId, sequenceNo))
                .map(this::toEntity);
    }

    @Override
    public List<RuntimeEventLedgerEntity> findByToolInvocationIdOrderBySequenceNo(String toolInvocationId) {
        return dao.queryByToolInvocationId(toolInvocationId)
                .stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public List<RuntimeEventLedgerEntity> findByRunIdOrderByInvocationAndSequenceNo(String runId) {
        return dao.queryByRunId(runId)
                .stream()
                .map(this::toEntity)
                .toList();
    }

    private RuntimeEventLedgerPO toPo(RuntimeEventLedgerEntity entity) {
        return RuntimeEventLedgerPO.builder()
                .runtimeEventId(entity.getRuntimeEventId())
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .requestId(entity.getRequestId())
                .toolInvocationId(entity.getToolInvocationId())
                .sequenceNo(entity.getSequenceNo())
                .upstreamEventId(entity.getUpstreamEventId())
                .eventType(entity.getEventType())
                .stage(entity.getStage())
                .status(entity.getStatus())
                .occurredAt(toLocalDateTime(entity.getOccurredAt()))
                .recordedAt(toLocalDateTime(entity.getRecordedAt()))
                .message(entity.getMessage())
                .presentationKind(entity.getPresentationKind())
                .payloadSchemaVersion(entity.getPayloadSchemaVersion())
                .payloadJson(entity.getPayloadJson())
                .artifactId(entity.getArtifactId())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .updateTime(toLocalDateTime(entity.getUpdatedAt()))
                .build();
    }

    private RuntimeEventLedgerEntity toEntity(RuntimeEventLedgerPO persistent) {
        return RuntimeEventLedgerEntity.builder()
                .runtimeEventId(persistent.getRuntimeEventId())
                .runId(persistent.getRunId())
                .sessionId(persistent.getSessionId())
                .requestId(persistent.getRequestId())
                .toolInvocationId(persistent.getToolInvocationId())
                .sequenceNo(persistent.getSequenceNo())
                .upstreamEventId(persistent.getUpstreamEventId())
                .eventType(persistent.getEventType())
                .stage(persistent.getStage())
                .status(persistent.getStatus())
                .occurredAt(toInstant(persistent.getOccurredAt()))
                .recordedAt(toInstant(persistent.getRecordedAt()))
                .message(persistent.getMessage())
                .presentationKind(persistent.getPresentationKind())
                .payloadSchemaVersion(persistent.getPayloadSchemaVersion())
                .payloadJson(persistent.getPayloadJson())
                .artifactId(persistent.getArtifactId())
                .errorCode(persistent.getErrorCode())
                .errorMessage(persistent.getErrorMessage())
                .createdAt(toInstant(persistent.getCreateTime()))
                .updatedAt(toInstant(persistent.getUpdateTime()))
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
