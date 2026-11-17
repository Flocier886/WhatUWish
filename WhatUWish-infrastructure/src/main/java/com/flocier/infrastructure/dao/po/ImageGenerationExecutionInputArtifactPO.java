package com.flocier.infrastructure.dao.po;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationExecutionInputArtifactPO {
    private Long id;
    private String imageGenerationExecutionId;
    private String artifactId;
    private String inputRole;
    private Integer inputOrder;
    private LocalDateTime createTime;
}
