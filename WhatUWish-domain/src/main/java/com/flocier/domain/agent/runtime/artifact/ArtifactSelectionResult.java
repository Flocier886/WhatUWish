package com.flocier.domain.agent.runtime.artifact;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 最终摘要文本与实际交付 artifact 的分离结果。 */
@Value
@Builder
public class ArtifactSelectionResult {
    String summaryText;
    List<String> artifactRefs;
    List<ArtifactRuntimeBinding> fileList;
    boolean explicitSelection;
}
