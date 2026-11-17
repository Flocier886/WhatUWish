package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import lombok.Builder;
import lombok.Value;

/** Java 已完成授权的 Report 内部材料描述，只允许发往 Python Storage Backend。 */
@Value
@Builder
public class ReportInputArtifactDescriptor {
    String artifactId;
    String fileName;
    String mimeType;
    Long fileSize;
    String storageType;
    String storageKey;
}
