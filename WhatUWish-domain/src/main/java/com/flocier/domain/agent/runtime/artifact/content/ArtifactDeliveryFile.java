package com.flocier.domain.agent.runtime.artifact.content;

import lombok.Value;

/** 面向交付区的受控 Artifact 投影。 */
@Value
public class ArtifactDeliveryFile {
    String artifactId;
    String runId;
    String name;
    String artifactType;
    String mimeType;
    Long size;
    String summary;
    String sourceStage;
    ArtifactAccessState accessState;
    ArtifactPreviewDescriptor preview;
    boolean previewSupported;
    boolean downloadSupported;
}
