package com.flocier.domain.agent.runtime.artifact.content;

import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/** 用户文件服务的领域入口：先判定账本与授权，再交给内容存储 Port。 */
@Service
@RequiredArgsConstructor
public class ArtifactContentAccessService {
    private final IArtifactLedgerRepository artifactLedgerRepository;
    private final ArtifactAccessAuthorizer artifactAccessAuthorizer;
    private final ArtifactContentStore artifactContentStore;

    public ArtifactDeliveryFile metadata(String runId, String artifactId, ArtifactAccessSubject subject) {
        ResolvedArtifact resolved = resolve(runId, artifactId, subject);
        try {
            ArtifactStoredContent storedContent = artifactContentStore.inspect(resolved.getLocator());
            return ArtifactDeliveryFileMapper.fromLedger(resolved.getArtifact(), ArtifactAccessState.AVAILABLE,
                    storedContent.getSize(), true);
        } catch (ArtifactContentStoreException ignored) {
            return ArtifactDeliveryFileMapper.fromLedger(resolved.getArtifact(), ArtifactAccessState.UNAVAILABLE,
                    false);
        }
    }

    public ArtifactPreview preview(String runId, String artifactId, ArtifactAccessSubject subject) {
        ResolvedArtifact resolved = resolve(runId, artifactId, subject);
        try {
            ArtifactStoredContent storedContent = artifactContentStore.inspect(resolved.getLocator());
            ArtifactPreviewDescriptor preview = ArtifactPreviewPolicy.describe(
                    ArtifactAccessState.AVAILABLE,
                    resolved.getArtifact().getMimeType(),
                    resolved.getArtifact().getFileName(),
                    storedContent.getSize(),
                    ArtifactDeliveryFileMapper.declaredPreview(resolved.getArtifact().getMetadataJson())
            );
            if (preview == null) {
                throw new ArtifactContentAccessException(ArtifactAccessState.UNAVAILABLE);
            }
            if (ArtifactPreviewPolicy.isBinaryPreview(preview)) {
                if (ArtifactPreviewDescriptor.SPREADSHEET_KIND.equals(preview.getKind())
                        && ArtifactPreviewPolicy.isCsv(resolved.getArtifact().getMimeType(), resolved.getArtifact().getFileName())) {
                    byte[] bytes = artifactContentStore.readPreview(resolved.getLocator(),
                            Math.toIntExact(ArtifactPreviewPolicy.CSV_PREVIEW_LIMIT_BYTES));
                    if (!ArtifactPreviewPolicy.isUtf8Text(bytes))
                        throw new ArtifactContentAccessException(ArtifactAccessState.UNAVAILABLE);
                    return ArtifactPreview.text(preview, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                }
                return ArtifactPreview.binary(preview, normalizedMime(resolved.getArtifact().getMimeType()),
                        artifactContentStore.openDownload(resolved.getLocator()));
            }
            return ArtifactPreview.text(preview, new String(artifactContentStore.readPreview(resolved.getLocator(),
                    Math.toIntExact(ArtifactPreviewDescriptor.HTML_KIND.equals(preview.getKind())
                            ? ArtifactPreviewPolicy.HTML_PREVIEW_LIMIT_BYTES : ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES)),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (ArtifactContentStoreException exception) {
            throw new ArtifactContentAccessException(ArtifactAccessState.UNAVAILABLE);
        }
    }

    private String normalizedMime(String mimeType) {
        return StringUtils.defaultIfBlank(mimeType, "application/octet-stream").split(";", 2)[0].trim();
    }

    public ArtifactDownload download(String runId, String artifactId, ArtifactAccessSubject subject) {
        ResolvedArtifact resolved = resolve(runId, artifactId, subject);
        try {
            InputStream stream = artifactContentStore.openDownload(resolved.getLocator());
            return new ArtifactDownload(resolved.getArtifact().getFileName(), resolved.getArtifact().getMimeType(), stream);
        } catch (ArtifactContentStoreException exception) {
            throw new ArtifactContentAccessException(ArtifactAccessState.UNAVAILABLE);
        }
    }

    private ResolvedArtifact resolve(String runId, String artifactId, ArtifactAccessSubject subject) {
        if (StringUtils.isBlank(runId) || StringUtils.isBlank(artifactId)) {
            throw new ArtifactContentAccessException(ArtifactAccessState.NOT_FOUND);
        }
        ArtifactLedgerEntity artifact = artifactLedgerRepository.findByRunId(runId).stream()
                .filter(item -> artifactId.equals(item.getArtifactId())).findFirst()
                .orElseThrow(() -> new ArtifactContentAccessException(ArtifactAccessState.NOT_FOUND));
        if (!deliverable(artifact)) {
            throw new ArtifactContentAccessException(ArtifactAccessState.NOT_FOUND);
        }
        if (!artifactAccessAuthorizer.isAllowed(subject, artifact, runId)) {
            throw new ArtifactContentAccessException(ArtifactAccessState.FORBIDDEN);
        }
        ArtifactContentLocator locator = new ArtifactContentLocator(artifact.getStorageType(),
                artifact.getStorageKey(), artifact.getStorageRef());
        if (locator.isEmpty()) {
            throw new ArtifactContentAccessException(ArtifactAccessState.UNAVAILABLE);
        }
        return new ResolvedArtifact(artifact, locator);
    }

    private boolean deliverable(ArtifactLedgerEntity artifact) {
        return artifact != null && !Integer.valueOf(1).equals(artifact.getDeleted())
                && "visible".equalsIgnoreCase(artifact.getVisibility())
                && "output".equalsIgnoreCase(artifact.getArtifactRole())
                && !"llm_response".equalsIgnoreCase(artifact.getArtifactType())
                && !"tool_result".equalsIgnoreCase(artifact.getArtifactType())
                && !"inline_summary".equalsIgnoreCase(artifact.getStorageType());
    }

    @Value
    private static class ResolvedArtifact {
        ArtifactLedgerEntity artifact;
        ArtifactContentLocator locator;
    }

    @Value
    public static class ArtifactDownload {
        String fileName;
        String mimeType;
        InputStream stream;
    }

    @Value
    public static class ArtifactPreview {
        ArtifactPreviewDescriptor descriptor;
        String mimeType;
        String text;
        InputStream stream;

        public static ArtifactPreview text(ArtifactPreviewDescriptor descriptor, String text) {
            return new ArtifactPreview(descriptor, "text/plain", text, null);
        }

        public static ArtifactPreview binary(ArtifactPreviewDescriptor descriptor, String mimeType, InputStream stream) {
            return new ArtifactPreview(descriptor, mimeType, null, stream);
        }

        public boolean isBinary() { return stream != null; }
    }
}
