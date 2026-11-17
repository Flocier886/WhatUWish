package com.flocier.infrastructure.adapter.repository;

import com.flocier.domain.ledger.adapter.repository.IDeepSearchExecutionRepository;
import com.flocier.domain.ledger.model.entity.DeepSearchExecutionEntity;
import com.flocier.infrastructure.dao.IDeepSearchExecutionDao;
import com.flocier.infrastructure.dao.po.DeepSearchExecutionPO;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "mysql")
public class MysqlDeepSearchExecutionRepository implements IDeepSearchExecutionRepository {

    @Resource
    private IDeepSearchExecutionDao dao;

    @Override
    public void save(DeepSearchExecutionEntity entity) {
        dao.insert(toPo(entity));
    }

    @Override
    public void update(DeepSearchExecutionEntity entity) {
        int affected = dao.update(toPo(entity));
        if (affected != 1) {
            throw new IllegalStateException("DeepSearch execution concurrent update conflict");
        }
    }

    @Override
    public Optional<DeepSearchExecutionEntity> findByToolInvocationId(String toolInvocationId) {
        return Optional.ofNullable(dao.queryByToolInvocationId(toolInvocationId))
                .map(this::toEntity);
    }

    private DeepSearchExecutionPO toPo(DeepSearchExecutionEntity entity) {
        return DeepSearchExecutionPO.builder()
                .deepSearchExecutionId(entity.getDeepSearchExecutionId())
                .toolInvocationId(entity.getToolInvocationId())
                .pythonExecutionId(entity.getPythonExecutionId())
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .requestId(entity.getRequestId())
                .queryText(entity.getQueryText())
                .researchMode(entity.getResearchMode())
                .status(entity.getStatus())
                .finalSummary(entity.getFinalSummary())
                .reportArtifactId(entity.getReportArtifactId())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .lastSequenceNo(entity.getLastSequenceNo())
                .lastUpstreamEventId(entity.getLastUpstreamEventId())
                .schemaVersion(entity.getSchemaVersion())
                .startedAt(toLocalDateTime(entity.getStartedAt()))
                .finishedAt(toLocalDateTime(entity.getFinishedAt()))
                .versionNo(entity.getVersionNo())
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .updateTime(toLocalDateTime(entity.getUpdatedAt()))
                .build();
    }

    private DeepSearchExecutionEntity toEntity(DeepSearchExecutionPO persistent) {
        return DeepSearchExecutionEntity.builder()
                .deepSearchExecutionId(persistent.getDeepSearchExecutionId())
                .toolInvocationId(persistent.getToolInvocationId())
                .pythonExecutionId(persistent.getPythonExecutionId())
                .runId(persistent.getRunId())
                .sessionId(persistent.getSessionId())
                .requestId(persistent.getRequestId())
                .queryText(persistent.getQueryText())
                .researchMode(persistent.getResearchMode())
                .status(persistent.getStatus())
                .finalSummary(persistent.getFinalSummary())
                .reportArtifactId(persistent.getReportArtifactId())
                .errorCode(persistent.getErrorCode())
                .errorMessage(persistent.getErrorMessage())
                .lastSequenceNo(persistent.getLastSequenceNo())
                .lastUpstreamEventId(persistent.getLastUpstreamEventId())
                .schemaVersion(persistent.getSchemaVersion())
                .startedAt(toInstant(persistent.getStartedAt()))
                .finishedAt(toInstant(persistent.getFinishedAt()))
                .versionNo(persistent.getVersionNo())
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
