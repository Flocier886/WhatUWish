package com.flocier.application.agent.imagegeneration.workbench;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 图片工作台直接长请求的受控结果，不携带 Runtime Run 或存储定位。 */
@Value
@Builder
public class WorkbenchImageGenerationResult {
    String executionId;
    String status;
    String summary;
    String mode;
    String size;
    Integer requestedCount;
    Integer generatedCount;
    Integer referenceCount;
    Integer maskCount;
    Boolean usedFallback;
    String errorCode;
    String errorMessage;
    List<WorkbenchImageArtifact> artifacts;
}
