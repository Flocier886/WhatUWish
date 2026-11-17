package com.flocier.infrastructure.adapter.repository;

import com.flocier.domain.ledger.adapter.repository.IReportExecutionRepository;
import com.flocier.domain.ledger.model.entity.ReportExecutionEntity;
import com.flocier.domain.ledger.model.entity.ReportExecutionInputArtifactEntity;
import com.flocier.infrastructure.dao.IReportExecutionDao;
import com.flocier.infrastructure.dao.po.ReportExecutionInputArtifactPO;
import com.flocier.infrastructure.dao.po.ReportExecutionPO;
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
public class MysqlReportExecutionRepository implements IReportExecutionRepository {

    @Resource
    private IReportExecutionDao dao;

    @Override
    public void save(ReportExecutionEntity entity) {
        if (dao.insert(toPo(entity)) != 1) {
            throw new IllegalStateException("Report execution insert count mismatch");
        }
    }

    @Override
    public void saveInputArtifacts(List<ReportExecutionInputArtifactEntity> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        int affected = dao.insertInputs(inputs.stream().map(this::toInputPo).toList());
        if (affected != inputs.size()) {
            throw new IllegalStateException("Report execution input artifact insert count mismatch");
        }
    }

    @Override
    public void update(ReportExecutionEntity entity) {
        int affected = dao.update(toPo(entity));
        if (affected != 1) {
            throw new IllegalStateException("Report execution concurrent update conflict");
        }
    }

    @Override
    public Optional<ReportExecutionEntity> findByToolInvocationId(String toolInvocationId) {
        return Optional.ofNullable(dao.queryByToolInvocationId(toolInvocationId)).map(this::toEntity);
    }

    @Override
    public List<ReportExecutionInputArtifactEntity> findInputsByReportExecutionId(String reportExecutionId) {
        List<ReportExecutionInputArtifactPO> values = dao.queryInputsByReportExecutionId(reportExecutionId);
        return values == null ? List.of() : values.stream().map(this::toInputEntity).toList();
    }

    private ReportExecutionPO toPo(ReportExecutionEntity entity) {
        return ReportExecutionPO.builder()
                .reportExecutionId(entity.getReportExecutionId())
                .toolInvocationId(entity.getToolInvocationId())
                .pythonExecutionId(entity.getPythonExecutionId())
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .requestId(entity.getRequestId())
                .taskSummary(entity.getTaskSummary())
                .outputType(entity.getOutputType())
                .outputFileName(entity.getOutputFileName())
                .status(entity.getStatus())
                .inputArtifactCount(entity.getInputArtifactCount())
                .outputArtifactId(entity.getOutputArtifactId())
                .finalSummary(entity.getFinalSummary())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .startedAt(toLocalDateTime(entity.getStartedAt()))
                .finishedAt(toLocalDateTime(entity.getFinishedAt()))
                .versionNo(entity.getVersionNo())
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .updateTime(toLocalDateTime(entity.getUpdatedAt()))
                .build();
    }

    private ReportExecutionInputArtifactPO toInputPo(ReportExecutionInputArtifactEntity entity) {
        return ReportExecutionInputArtifactPO.builder()
                .reportExecutionId(entity.getReportExecutionId())
                .artifactId(entity.getArtifactId())
                .inputOrder(entity.getInputOrder())
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .build();
    }

    private ReportExecutionEntity toEntity(ReportExecutionPO value) {
        return ReportExecutionEntity.builder()
                .reportExecutionId(value.getReportExecutionId())
                .toolInvocationId(value.getToolInvocationId())
                .pythonExecutionId(value.getPythonExecutionId())
                .runId(value.getRunId())
                .sessionId(value.getSessionId())
                .requestId(value.getRequestId())
                .taskSummary(value.getTaskSummary())
                .outputType(value.getOutputType())
                .outputFileName(value.getOutputFileName())
                .status(value.getStatus())
                .inputArtifactCount(value.getInputArtifactCount())
                .outputArtifactId(value.getOutputArtifactId())
                .finalSummary(value.getFinalSummary())
                .errorCode(value.getErrorCode())
                .errorMessage(value.getErrorMessage())
                .startedAt(toInstant(value.getStartedAt()))
                .finishedAt(toInstant(value.getFinishedAt()))
                .versionNo(value.getVersionNo())
                .createdAt(toInstant(value.getCreateTime()))
                .updatedAt(toInstant(value.getUpdateTime()))
                .build();
    }

    private ReportExecutionInputArtifactEntity toInputEntity(ReportExecutionInputArtifactPO value) {
        return ReportExecutionInputArtifactEntity.builder()
                .reportExecutionId(value.getReportExecutionId())
                .artifactId(value.getArtifactId())
                .inputOrder(value.getInputOrder())
                .createdAt(toInstant(value.getCreateTime()))
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
