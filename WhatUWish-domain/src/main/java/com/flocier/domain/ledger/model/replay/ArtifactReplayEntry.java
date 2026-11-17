package com.flocier.domain.ledger.model.replay;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewPolicy;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import lombok.Getter;

/** 可见 artifact 的白名单元数据回放条目。 */
@Getter
public final class ArtifactReplayEntry implements ReplayTimelineEntry {

    private final ReplayEntryMetadata metadata;
    private final String llmInvocationId;
    private final String toolInvocationId;
    private final String artifactId;
    private final String name;
    private final String artifactType;
    private final String mimeType;
    private final Long size;
    private final String summary;
    private final String visibility;
    private final String sourceToolInvocationId;
    private final String sourceStage;
    private final String accessState;
    private final boolean previewSupported;
    private final boolean downloadSupported;
    private final ArtifactPreviewDescriptor preview;

    public ArtifactReplayEntry(ReplayEntryMetadata metadata, String llmInvocationId, String toolInvocationId,
                               String artifactId, String name, String artifactType, String mimeType, Long size,
                               String summary, String visibility, String sourceToolInvocationId) {
        this(metadata, llmInvocationId, toolInvocationId, artifactId, name, artifactType, mimeType, size,
                summary, visibility, sourceToolInvocationId, null, "AVAILABLE",
                ArtifactPreviewPolicy.describe(ArtifactAccessState.AVAILABLE, mimeType, name, size) != null, true, null);
    }

    public ArtifactReplayEntry(ReplayEntryMetadata metadata, String llmInvocationId, String toolInvocationId,
                               String artifactId, String name, String artifactType, String mimeType, Long size,
                               String summary, String visibility, String sourceToolInvocationId, String sourceStage,
                               String accessState, boolean previewSupported, boolean downloadSupported) {
        this(metadata, llmInvocationId, toolInvocationId, artifactId, name, artifactType, mimeType, size,
                summary, visibility, sourceToolInvocationId, sourceStage, accessState, previewSupported,
                downloadSupported, null);
    }

    public ArtifactReplayEntry(ReplayEntryMetadata metadata, String llmInvocationId, String toolInvocationId,
                               String artifactId, String name, String artifactType, String mimeType, Long size,
                               String summary, String visibility, String sourceToolInvocationId, String sourceStage,
                               String accessState, boolean previewSupported, boolean downloadSupported,
                               ArtifactPreviewDescriptor preview) {
        this.metadata = metadata;
        this.llmInvocationId = llmInvocationId;
        this.toolInvocationId = toolInvocationId;
        this.artifactId = artifactId;
        this.name = name;
        this.artifactType = artifactType;
        this.mimeType = mimeType;
        this.size = size;
        this.summary = summary;
        this.visibility = visibility;
        this.sourceToolInvocationId = sourceToolInvocationId;
        this.sourceStage = sourceStage;
        this.accessState = accessState;
        this.previewSupported = previewSupported;
        this.downloadSupported = downloadSupported;
        this.preview = preview;
    }
}
