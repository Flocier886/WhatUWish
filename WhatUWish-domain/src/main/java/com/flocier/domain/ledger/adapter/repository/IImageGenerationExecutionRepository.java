package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionInputArtifactEntity;
import java.util.List;
import java.util.Optional;

/** 图片生成专属状态投影及其输入 Artifact 关系的持久化边界。 */
public interface IImageGenerationExecutionRepository {
    void save(ImageGenerationExecutionEntity entity);
    void saveInputArtifacts(List<ImageGenerationExecutionInputArtifactEntity> inputs);
    void update(ImageGenerationExecutionEntity entity);
    Optional<ImageGenerationExecutionEntity> findByToolInvocationId(String toolInvocationId);
    List<ImageGenerationExecutionEntity> findPageByExecutionSourceAndSession(String executionSource, String sessionId, int offset, int limit);
    long countByExecutionSourceAndSession(String executionSource, String sessionId);
    List<ImageGenerationExecutionInputArtifactEntity> findInputsByImageGenerationExecutionId(String imageGenerationExecutionId);
}
