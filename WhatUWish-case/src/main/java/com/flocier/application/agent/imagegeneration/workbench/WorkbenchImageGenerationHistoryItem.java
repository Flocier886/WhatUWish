package com.flocier.application.agent.imagegeneration.workbench;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 工作台单次图片创作的受控历史投影，不使用 Agent Run 语义。 */
@Value
@Builder
public class WorkbenchImageGenerationHistoryItem {
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
    Instant startedAt;
    Instant finishedAt;
    List<WorkbenchImageArtifact> artifacts;
}
