package com.flocier.domain.ledger.model.entity;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 图片生成专属执行与参考图/遮罩图 Artifact 的稳定有序关联。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationExecutionInputArtifactEntity {
    private String imageGenerationExecutionId;
    private String artifactId;
    private String inputRole;
    private Integer inputOrder;
    private Instant createdAt;
}
