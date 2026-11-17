package com.flocier.domain.agent.runtime.artifact.content;

import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeBinding;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.replay.ArtifactReplayEntry;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

/** 统一生成面向用户的 Artifact 交付投影，不传播任何内部存储定位信息。 */
public final class ArtifactDeliveryFileMapper {
    private ArtifactDeliveryFileMapper() {
    }

    public static ArtifactDeliveryFile fromLedger(ArtifactLedgerEntity artifact, ArtifactAccessState accessState,
                                                   boolean downloadSupported) {
        return fromLedger(artifact, accessState, ledgerSize(artifact), downloadSupported);
    }

    public static ArtifactDeliveryFile fromLedger(ArtifactLedgerEntity artifact, ArtifactAccessState accessState,
                                                   Long authoritativeSize, boolean downloadSupported) {
        Long size = authoritativeSize == null ? ledgerSize(artifact) : authoritativeSize;
        return delivery(artifact.getArtifactId(), artifact.getRunId(),
                StringUtils.firstNonBlank(artifact.getArtifactName(), artifact.getFileName(), "未命名产物"),
                artifact.getArtifactType(), artifact.getMimeType(),
                size,
                artifact.getContentSummary(), artifact.getSourceName(), accessState,
                artifact.getFileName(), downloadSupported, declaredPreview(artifact.getMetadataJson()));
    }

    public static ArtifactDeliveryFile fromReplay(ArtifactReplayEntry artifact) {
        return delivery(artifact.getArtifactId(), artifact.getMetadata().getRunId(), artifact.getName(),
                artifact.getArtifactType(), artifact.getMimeType(), artifact.getSize(), artifact.getSummary(),
                artifact.getSourceStage(), accessState(artifact.getAccessState()),
                artifact.getName(), artifact.isDownloadSupported(), artifact.getPreview());
    }

    public static ArtifactDeliveryFile fromBinding(ArtifactRuntimeBinding binding) {
        if (binding == null || binding.getDescriptor() == null) {
            return null;
        }
        ArtifactRuntimeDescriptor descriptor = binding.getDescriptor();
        ArtifactRuntimeSource source = binding.getSource();
        return delivery(descriptor.getArtifactId(), source == null ? null : source.getRunId(),
                StringUtils.firstNonBlank(descriptor.getName(), descriptor.getFileName(), "未命名产物"),
                descriptor.getArtifactType(), descriptor.getMimeType(), descriptor.getFileSize(),
                descriptor.getContentSummary(), StringUtils.firstNonBlank(descriptor.getSourceName(),
                        source == null ? null : source.getToolName()), ArtifactAccessState.AVAILABLE,
                descriptor.getFileName(), true, declaredPreview(descriptor.getMetadata()));
    }

    private static ArtifactDeliveryFile delivery(String artifactId, String runId, String name, String artifactType,
                                                   String mimeType, Long size, String summary, String sourceStage,
                                                   ArtifactAccessState accessState, String fileName,
                                                   boolean downloadSupported, ArtifactPreviewDescriptor declaredPreview) {
        ArtifactPreviewDescriptor preview = ArtifactPreviewPolicy.describe(
                accessState, mimeType, fileName, size, declaredPreview
        );
        return new ArtifactDeliveryFile(artifactId, runId, name, artifactType, mimeType, size, summary,
                sourceStage, accessState, preview, preview != null, downloadSupported);
    }

    static ArtifactPreviewDescriptor declaredPreview(String metadataJson) {
        if (StringUtils.isBlank(metadataJson)) {
            return null;
        }
        try {
            JSONObject metadata = JSON.parseObject(metadataJson);
            if (metadata == null) {
                return null;
            }
            Object preview = metadata.get("preview");
            return preview instanceof java.util.Map<?, ?> value ? previewDescriptor(value) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ArtifactPreviewDescriptor declaredPreview(java.util.Map<?, ?> metadata) {
        if (metadata == null) {
            return null;
        }
        Object rawPreview = metadata.get("preview");
        return rawPreview instanceof java.util.Map<?, ?> preview ? previewDescriptor(preview) : null;
    }

    private static ArtifactPreviewDescriptor previewDescriptor(java.util.Map<?, ?> preview) {
        Object kind = preview.get("kind");
        Object schemaVersion = preview.get("schemaVersion");
        if (!(kind instanceof String) || !(schemaVersion instanceof String)) {
            return null;
        }
        return new ArtifactPreviewDescriptor((String) kind, (String) schemaVersion);
    }

    private static Long ledgerSize(ArtifactLedgerEntity artifact) {
        return artifact.getFileSize() == null ? artifact.getSize() : artifact.getFileSize();
    }

    private static ArtifactAccessState accessState(String value) {
        try {
            return ArtifactAccessState.valueOf(StringUtils.defaultIfBlank(value, "UNAVAILABLE"));
        } catch (IllegalArgumentException ignored) {
            return ArtifactAccessState.UNAVAILABLE;
        }
    }
}
