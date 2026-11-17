package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.IReportExecutionRepository;
import com.flocier.domain.ledger.model.entity.ReportExecutionEntity;
import com.flocier.domain.ledger.model.entity.ReportExecutionInputArtifactEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryReportExecutionRepository implements IReportExecutionRepository {

    private final ConcurrentHashMap<String, ReportExecutionEntity> executions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ReportExecutionInputArtifactEntity>> inputs = new ConcurrentHashMap<>();

    @Override
    public void save(ReportExecutionEntity entity) {
        if (entity == null || executions.putIfAbsent(entity.getToolInvocationId(), copy(entity)) != null) {
            throw new IllegalStateException("Report execution already exists");
        }
    }

    @Override
    public void saveInputArtifacts(List<ReportExecutionInputArtifactEntity> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String executionId = values.get(0).getReportExecutionId();
        List<ReportExecutionInputArtifactEntity> copied = values.stream().map(this::copy)
                .sorted(Comparator.comparing(ReportExecutionInputArtifactEntity::getInputOrder)).toList();
        if (copied.stream().map(ReportExecutionInputArtifactEntity::getArtifactId).distinct().count() != copied.size()) {
            throw new IllegalStateException("Report execution input artifact duplicates");
        }
        if (copied.stream().map(ReportExecutionInputArtifactEntity::getInputOrder).distinct().count() != copied.size()) {
            throw new IllegalStateException("Report execution input artifact order duplicates");
        }
        if (inputs.putIfAbsent(executionId, copied) != null) {
            throw new IllegalStateException("Report execution input artifacts already exist");
        }
    }

    @Override
    public void update(ReportExecutionEntity entity) {
        if (entity == null) {
            throw new IllegalStateException("Report execution does not exist");
        }
        executions.compute(entity.getToolInvocationId(), (ignored, current) -> {
            if (current == null) {
                throw new IllegalStateException("Report execution does not exist");
            }
            long expectedVersion = (current.getVersionNo() == null ? 0L : current.getVersionNo()) + 1L;
            if (entity.getVersionNo() == null || entity.getVersionNo() != expectedVersion) {
                throw new IllegalStateException("Report execution concurrent update conflict");
            }
            return copy(entity);
        });
    }

    @Override
    public Optional<ReportExecutionEntity> findByToolInvocationId(String toolInvocationId) {
        return Optional.ofNullable(executions.get(toolInvocationId)).map(this::copy);
    }

    @Override
    public List<ReportExecutionInputArtifactEntity> findInputsByReportExecutionId(String reportExecutionId) {
        return inputs.getOrDefault(reportExecutionId, List.of()).stream().map(this::copy).toList();
    }

    private ReportExecutionEntity copy(ReportExecutionEntity source) {
        return ReportExecutionEntity.builder()
                .reportExecutionId(source.getReportExecutionId())
                .toolInvocationId(source.getToolInvocationId())
                .pythonExecutionId(source.getPythonExecutionId())
                .runId(source.getRunId())
                .sessionId(source.getSessionId())
                .requestId(source.getRequestId())
                .taskSummary(source.getTaskSummary())
                .outputType(source.getOutputType())
                .outputFileName(source.getOutputFileName())
                .status(source.getStatus())
                .inputArtifactCount(source.getInputArtifactCount())
                .outputArtifactId(source.getOutputArtifactId())
                .finalSummary(source.getFinalSummary())
                .errorCode(source.getErrorCode())
                .errorMessage(source.getErrorMessage())
                .startedAt(source.getStartedAt())
                .finishedAt(source.getFinishedAt())
                .versionNo(source.getVersionNo())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }

    private ReportExecutionInputArtifactEntity copy(ReportExecutionInputArtifactEntity source) {
        return ReportExecutionInputArtifactEntity.builder()
                .reportExecutionId(source.getReportExecutionId())
                .artifactId(source.getArtifactId())
                .inputOrder(source.getInputOrder())
                .createdAt(source.getCreatedAt())
                .build();
    }
}
