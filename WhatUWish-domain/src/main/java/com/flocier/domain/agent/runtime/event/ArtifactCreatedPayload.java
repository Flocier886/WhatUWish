package com.flocier.domain.agent.runtime.event;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import lombok.Getter;

/** artifact 创建事件的受控展示数据。 */
@Getter
public final class ArtifactCreatedPayload implements RuntimeEventPayload {

    private final String artifactId;
    private final String reference;
    private final String name;
    private final String artifactType;
    private final String mimeType;
    private final Long size;
    private final String summary;
    private final String visibility;
    private final String sourceToolInvocationId;
    private final String sourceStage;
    private final String accessState;
    private final ArtifactPreviewDescriptor preview;
    private final boolean previewSupported;
    private final boolean downloadSupported;

    public ArtifactCreatedPayload(String artifactId, String reference, String name, String artifactType,
                                  String mimeType, Long size, String summary, String visibility,
                                  String sourceToolInvocationId, String sourceStage, String accessState,
                                  ArtifactPreviewDescriptor preview, boolean previewSupported,
                                  boolean downloadSupported) {
        this.artifactId = artifactId;
        this.reference = reference;
        this.name = name;
        this.artifactType = artifactType;
        this.mimeType = mimeType;
        this.size = size;
        this.summary = summary;
        this.visibility = visibility;
        this.sourceToolInvocationId = sourceToolInvocationId;
        this.sourceStage = sourceStage;
        this.accessState = accessState;
        this.preview = preview;
        this.previewSupported = previewSupported;
        this.downloadSupported = downloadSupported;
    }
}
