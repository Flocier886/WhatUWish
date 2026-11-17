package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Report 专属执行与输入 Artifact 的稳定有序关联。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExecutionInputArtifactEntity {
    private String reportExecutionId;
    private String artifactId;
    private Integer inputOrder;
    private Instant createdAt;
}
