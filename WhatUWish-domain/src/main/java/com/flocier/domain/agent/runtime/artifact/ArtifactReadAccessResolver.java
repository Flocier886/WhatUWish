package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/** Agent 正文读取的账本授权与内部 locator 解析边界。 */
@Service
@RequiredArgsConstructor
public class ArtifactReadAccessResolver {

    private static final long MAX_READABLE_ARTIFACT_BYTES = 2L * 1024L * 1024L;
    private static final List<String> TEXT_MIME_TYPES = List.of(
            "text/plain", "text/markdown", "text/x-markdown", "text/html", "text/csv", "application/csv"
    );
    private static final List<String> TEXT_SUFFIXES = List.of(".md", ".markdown", ".txt", ".html", ".htm", ".csv");

    private final IArtifactLedgerRepository artifactLedgerRepository;

    public ArtifactLedgerEntity resolve(AgentContext context, String artifactId) {
        if (context == null || StringUtils.isBlank(context.getSessionId())) {
            throw new ArtifactReadAccessException("artifact_read_context_invalid", "当前运行上下文不完整，无法读取产物。");
        }
        if (StringUtils.isBlank(artifactId)) {
            throw new ArtifactReadAccessException("artifact_read_invalid_argument", "请提供要读取的 Artifact ID。");
        }

        ArtifactLedgerEntity artifact = artifactLedgerRepository.findByArtifactId(artifactId)
                .orElseThrow(() -> new ArtifactReadAccessException(
                        "artifact_read_not_found", "指定的 Artifact 不存在或当前不可访问。"
                ));
        if (!context.getSessionId().equals(artifact.getSessionId())
                || Integer.valueOf(1).equals(artifact.getDeleted())
                || !"visible".equalsIgnoreCase(artifact.getVisibility())
                || !("output".equalsIgnoreCase(artifact.getArtifactRole()) || isWebFetchSource(artifact))) {
            throw new ArtifactReadAccessException("artifact_read_forbidden", "指定的 Artifact 不属于当前可访问运行。");
        }

        Long size = artifact.getFileSize() == null ? artifact.getSize() : artifact.getFileSize();
        if (size == null || size < 0 || size > MAX_READABLE_ARTIFACT_BYTES || !isSupportedText(artifact)) {
            throw new ArtifactReadAccessException("artifact_read_unsupported", "当前 Artifact 不是可读取的文本交付文件。");
        }
        if (StringUtils.isAnyBlank(artifact.getStorageType(), artifact.getStorageKey())) {
            throw new ArtifactReadAccessException("artifact_read_unavailable", "当前 Artifact 的存储内容不可用。");
        }
        return artifact;
    }

    private boolean isSupportedText(ArtifactLedgerEntity artifact) {
        String mime = StringUtils.defaultString(artifact.getMimeType()).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String fileName = StringUtils.defaultString(artifact.getFileName()).toLowerCase(Locale.ROOT);
        return TEXT_MIME_TYPES.contains(mime) || TEXT_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }

    private boolean isWebFetchSource(ArtifactLedgerEntity artifact) {
        return "source".equalsIgnoreCase(artifact.getArtifactRole())
                && "web_page".equalsIgnoreCase(artifact.getArtifactType())
                && "web_fetch".equalsIgnoreCase(artifact.getSourceType())
                && "web_fetch".equalsIgnoreCase(artifact.getSourceName());
    }
}
