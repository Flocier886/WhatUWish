package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.IImageGenerationExecutionRepository;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionInputArtifactEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryImageGenerationExecutionRepository implements IImageGenerationExecutionRepository {

    private final ConcurrentHashMap<String, ImageGenerationExecutionEntity> executions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> executionIdsByToolInvocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ImageGenerationExecutionInputArtifactEntity>> inputs = new ConcurrentHashMap<>();

    @Override public void save(ImageGenerationExecutionEntity entity) {
        if (entity == null || entity.getImageGenerationExecutionId() == null
                || executions.putIfAbsent(entity.getImageGenerationExecutionId(), copy(entity)) != null) {
            throw new IllegalStateException("Image generation execution already exists");
        }
        if (entity.getToolInvocationId() != null
                && executionIdsByToolInvocation.putIfAbsent(entity.getToolInvocationId(), entity.getImageGenerationExecutionId()) != null) {
            executions.remove(entity.getImageGenerationExecutionId());
            throw new IllegalStateException("Image generation tool invocation already has an execution");
        }
    }

    @Override public void saveInputArtifacts(List<ImageGenerationExecutionInputArtifactEntity> values) {
        if (values == null || values.isEmpty()) return;
        String executionId = values.get(0).getImageGenerationExecutionId();
        List<ImageGenerationExecutionInputArtifactEntity> copied = values.stream().map(this::copy)
                .sorted(Comparator.comparing(ImageGenerationExecutionInputArtifactEntity::getInputRole)
                        .thenComparing(ImageGenerationExecutionInputArtifactEntity::getInputOrder)).toList();
        if (copied.stream().anyMatch(item -> !executionId.equals(item.getImageGenerationExecutionId()))
                || copied.stream().map(item -> item.getInputRole() + ":" + item.getInputOrder()).distinct().count() != copied.size()
                || copied.stream().map(item -> item.getInputRole() + ":" + item.getArtifactId()).distinct().count() != copied.size()
                || inputs.putIfAbsent(executionId, copied) != null) {
            throw new IllegalStateException("Image generation execution input artifacts are invalid");
        }
    }

    @Override public void update(ImageGenerationExecutionEntity entity) {
        if (entity == null) throw new IllegalStateException("Image generation execution does not exist");
        executions.compute(entity.getImageGenerationExecutionId(), (ignored, current) -> {
            if (current == null) throw new IllegalStateException("Image generation execution does not exist");
            long expectedVersion = (current.getVersionNo() == null ? 0L : current.getVersionNo()) + 1L;
            if (entity.getVersionNo() == null || entity.getVersionNo() != expectedVersion) {
                throw new IllegalStateException("Image generation execution concurrent update conflict");
            }
            return copy(entity);
        });
    }

    @Override public Optional<ImageGenerationExecutionEntity> findByToolInvocationId(String toolInvocationId) {
        String executionId = executionIdsByToolInvocation.get(toolInvocationId);
        return Optional.ofNullable(executionId == null ? null : executions.get(executionId)).map(this::copy);
    }

    @Override public List<ImageGenerationExecutionEntity> findPageByExecutionSourceAndSession(String executionSource, String sessionId, int offset, int limit) {
        return executions.values().stream()
                .filter(value -> java.util.Objects.equals(executionSource, value.getExecutionSource())
                        && java.util.Objects.equals(sessionId, value.getSessionId()))
                .sorted(Comparator.comparing(ImageGenerationExecutionEntity::getStartedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ImageGenerationExecutionEntity::getImageGenerationExecutionId, Comparator.reverseOrder()))
                .skip(Math.max(offset, 0)).limit(Math.max(limit, 0)).map(this::copy).toList();
    }

    @Override public long countByExecutionSourceAndSession(String executionSource, String sessionId) {
        return executions.values().stream().filter(value -> java.util.Objects.equals(executionSource, value.getExecutionSource())
                && java.util.Objects.equals(sessionId, value.getSessionId())).count();
    }

    @Override public List<ImageGenerationExecutionInputArtifactEntity> findInputsByImageGenerationExecutionId(String executionId) {
        return inputs.getOrDefault(executionId, List.of()).stream().map(this::copy).toList();
    }

    private ImageGenerationExecutionEntity copy(ImageGenerationExecutionEntity value) {
        return ImageGenerationExecutionEntity.builder()
                .imageGenerationExecutionId(value.getImageGenerationExecutionId()).executionSource(value.getExecutionSource()).toolInvocationId(value.getToolInvocationId())
                .pythonExecutionId(value.getPythonExecutionId()).runId(value.getRunId()).sessionId(value.getSessionId())
                .requestId(value.getRequestId()).generationMode(value.getGenerationMode()).requestedSize(value.getRequestedSize())
                .requestedOutputCount(value.getRequestedOutputCount()).generatedOutputCount(value.getGeneratedOutputCount())
                .referenceArtifactCount(value.getReferenceArtifactCount()).maskArtifactCount(value.getMaskArtifactCount())
                .usedFallback(value.getUsedFallback()).status(value.getStatus()).finalSummary(value.getFinalSummary())
                .errorCode(value.getErrorCode()).errorMessage(value.getErrorMessage()).startedAt(value.getStartedAt())
                .finishedAt(value.getFinishedAt()).versionNo(value.getVersionNo()).createdAt(value.getCreatedAt())
                .updatedAt(value.getUpdatedAt()).build();
    }

    private ImageGenerationExecutionInputArtifactEntity copy(ImageGenerationExecutionInputArtifactEntity value) {
        return ImageGenerationExecutionInputArtifactEntity.builder().imageGenerationExecutionId(value.getImageGenerationExecutionId())
                .artifactId(value.getArtifactId()).inputRole(value.getInputRole()).inputOrder(value.getInputOrder())
                .createdAt(value.getCreatedAt()).build();
    }
}
