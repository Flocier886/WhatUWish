package com.flocier.infrastructure.adapter.repository;

import com.flocier.domain.ledger.adapter.repository.IImageGenerationExecutionRepository;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionInputArtifactEntity;
import com.flocier.infrastructure.dao.IImageGenerationExecutionDao;
import com.flocier.infrastructure.dao.po.ImageGenerationExecutionInputArtifactPO;
import com.flocier.infrastructure.dao.po.ImageGenerationExecutionPO;
import jakarta.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "mysql")
public class MysqlImageGenerationExecutionRepository implements IImageGenerationExecutionRepository {

    @Resource private IImageGenerationExecutionDao dao;

    @Override public void save(ImageGenerationExecutionEntity entity) {
        if (dao.insert(toPo(entity)) != 1) throw new IllegalStateException("Image generation execution insert count mismatch");
    }

    @Override public void saveInputArtifacts(List<ImageGenerationExecutionInputArtifactEntity> inputs) {
        if (inputs == null || inputs.isEmpty()) return;
        if (dao.insertInputs(inputs.stream().map(this::toInputPo).toList()) != inputs.size()) {
            throw new IllegalStateException("Image generation execution input artifact insert count mismatch");
        }
    }

    @Override public void update(ImageGenerationExecutionEntity entity) {
        if (dao.update(toPo(entity)) != 1) throw new IllegalStateException("Image generation execution concurrent update conflict");
    }

    @Override public Optional<ImageGenerationExecutionEntity> findByToolInvocationId(String toolInvocationId) {
        return Optional.ofNullable(dao.queryByToolInvocationId(toolInvocationId)).map(this::toEntity);
    }

    @Override public List<ImageGenerationExecutionEntity> findPageByExecutionSourceAndSession(String executionSource, String sessionId, int offset, int limit) {
        List<ImageGenerationExecutionPO> values = dao.queryPageByExecutionSourceAndSession(executionSource, sessionId, offset, limit);
        return values == null ? List.of() : values.stream().map(this::toEntity).toList();
    }

    @Override public long countByExecutionSourceAndSession(String executionSource, String sessionId) {
        return dao.countByExecutionSourceAndSession(executionSource, sessionId);
    }

    @Override public List<ImageGenerationExecutionInputArtifactEntity> findInputsByImageGenerationExecutionId(String executionId) {
        List<ImageGenerationExecutionInputArtifactPO> values = dao.queryInputsByImageGenerationExecutionId(executionId);
        return values == null ? List.of() : values.stream().map(this::toInputEntity).toList();
    }

    private ImageGenerationExecutionPO toPo(ImageGenerationExecutionEntity value) {
        return ImageGenerationExecutionPO.builder()
                .imageGenerationExecutionId(value.getImageGenerationExecutionId()).executionSource(value.getExecutionSource()).toolInvocationId(value.getToolInvocationId())
                .pythonExecutionId(value.getPythonExecutionId()).runId(value.getRunId()).sessionId(value.getSessionId())
                .requestId(value.getRequestId()).generationMode(value.getGenerationMode()).requestedSize(value.getRequestedSize())
                .requestedOutputCount(value.getRequestedOutputCount()).generatedOutputCount(value.getGeneratedOutputCount())
                .referenceArtifactCount(value.getReferenceArtifactCount()).maskArtifactCount(value.getMaskArtifactCount())
                .usedFallback(value.getUsedFallback()).status(value.getStatus()).finalSummary(value.getFinalSummary())
                .errorCode(value.getErrorCode()).errorMessage(value.getErrorMessage()).startedAt(toLocal(value.getStartedAt()))
                .finishedAt(toLocal(value.getFinishedAt())).versionNo(value.getVersionNo()).createTime(toLocal(value.getCreatedAt()))
                .updateTime(toLocal(value.getUpdatedAt())).build();
    }

    private ImageGenerationExecutionInputArtifactPO toInputPo(ImageGenerationExecutionInputArtifactEntity value) {
        return ImageGenerationExecutionInputArtifactPO.builder().imageGenerationExecutionId(value.getImageGenerationExecutionId())
                .artifactId(value.getArtifactId()).inputRole(value.getInputRole()).inputOrder(value.getInputOrder())
                .createTime(toLocal(value.getCreatedAt())).build();
    }

    private ImageGenerationExecutionEntity toEntity(ImageGenerationExecutionPO value) {
        return ImageGenerationExecutionEntity.builder()
                .imageGenerationExecutionId(value.getImageGenerationExecutionId()).executionSource(value.getExecutionSource()).toolInvocationId(value.getToolInvocationId())
                .pythonExecutionId(value.getPythonExecutionId()).runId(value.getRunId()).sessionId(value.getSessionId())
                .requestId(value.getRequestId()).generationMode(value.getGenerationMode()).requestedSize(value.getRequestedSize())
                .requestedOutputCount(value.getRequestedOutputCount()).generatedOutputCount(value.getGeneratedOutputCount())
                .referenceArtifactCount(value.getReferenceArtifactCount()).maskArtifactCount(value.getMaskArtifactCount())
                .usedFallback(value.getUsedFallback()).status(value.getStatus()).finalSummary(value.getFinalSummary())
                .errorCode(value.getErrorCode()).errorMessage(value.getErrorMessage()).startedAt(toInstant(value.getStartedAt()))
                .finishedAt(toInstant(value.getFinishedAt())).versionNo(value.getVersionNo()).createdAt(toInstant(value.getCreateTime()))
                .updatedAt(toInstant(value.getUpdateTime())).build();
    }

    private ImageGenerationExecutionInputArtifactEntity toInputEntity(ImageGenerationExecutionInputArtifactPO value) {
        return ImageGenerationExecutionInputArtifactEntity.builder().imageGenerationExecutionId(value.getImageGenerationExecutionId())
                .artifactId(value.getArtifactId()).inputRole(value.getInputRole()).inputOrder(value.getInputOrder())
                .createdAt(toInstant(value.getCreateTime())).build();
    }

    private LocalDateTime toLocal(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault()); }
    private Instant toInstant(LocalDateTime value) { return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant(); }
}
