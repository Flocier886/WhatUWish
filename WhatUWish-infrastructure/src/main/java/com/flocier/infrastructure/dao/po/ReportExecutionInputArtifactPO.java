package com.flocier.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExecutionInputArtifactPO {
    private Long id;
    private String reportExecutionId;
    private String artifactId;
    private Integer inputOrder;
    private LocalDateTime createTime;
}
