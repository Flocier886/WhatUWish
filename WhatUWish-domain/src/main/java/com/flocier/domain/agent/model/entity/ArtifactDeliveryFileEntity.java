package com.flocier.domain.agent.model.entity;

import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeBinding;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFileMapper;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import lombok.Builder;
import lombok.Value;

/** 最终响应中单个可交付 artifact 的元数据。 */
@Value
@Builder
public class ArtifactDeliveryFileEntity {
    String artifactKey;
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

    public static ArtifactDeliveryFileEntity from(ArtifactRuntimeBinding binding) {
        if (binding == null || binding.getDescriptor() == null) {
            return null;
        }
        ArtifactDeliveryFile delivery = ArtifactDeliveryFileMapper.fromBinding(binding);
        return ArtifactDeliveryFileEntity.builder()
                .artifactKey(binding.artifactKey())
                .artifactId(delivery.getArtifactId())
                .runId(delivery.getRunId())
                .name(delivery.getName())
                .artifactType(delivery.getArtifactType())
                .mimeType(delivery.getMimeType())
                .size(delivery.getSize())
                .summary(delivery.getSummary())
                .sourceStage(delivery.getSourceStage())
                .accessState(delivery.getAccessState())
                .preview(delivery.getPreview())
                .previewSupported(delivery.isPreviewSupported())
                .downloadSupported(delivery.isDownloadSupported())
                .build();
    }
}
