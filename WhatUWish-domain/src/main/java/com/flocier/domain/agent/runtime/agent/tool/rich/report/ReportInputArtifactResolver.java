package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/** Report 输入材料的授权与 locator 解析边界；Python 不自行枚举、授权或猜测 Artifact。 */
@Service
@RequiredArgsConstructor
public class ReportInputArtifactResolver {
    private static final long MAX_SINGLE_ARTIFACT_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_TOTAL_ARTIFACT_BYTES = 8L * 1024L * 1024L;
    private static final List<String> TEXT_MIME_TYPES = List.of(
            "text/plain", "text/markdown", "text/x-markdown", "text/html", "text/csv", "application/csv"
    );
    private static final List<String> TEXT_SUFFIXES = List.of(".md", ".markdown", ".txt", ".html", ".htm", ".csv");

    private final IArtifactLedgerRepository artifactLedgerRepository;

    public List<ReportInputArtifactDescriptor> resolve(AgentContext context, ReportStartRequest request) {
        if (context == null || request == null || StringUtils.isBlank(context.getSessionId())) {
            throw new ReportStreamException("report_input_invalid", "Report 输入缺少当前运行上下文");
        }
        Object runIdAttribute = context.getAttribute("runId");
        String runId = runIdAttribute == null ? null : String.valueOf(runIdAttribute);
        if (StringUtils.isBlank(runId)) {
            throw new ReportStreamException("report_input_invalid", "Report 输入缺少当前 Run 标识");
        }
        long totalBytes = 0L;
        List<ReportInputArtifactDescriptor> resolved = new ArrayList<>();
        for (String artifactId : request.getInputArtifactIds()) {
            ArtifactLedgerEntity artifact = artifactLedgerRepository.findByArtifactId(artifactId)
                    .orElseThrow(() -> new ReportStreamException("report_input_not_found", "指定的输入 Artifact 不存在或不可访问"));
            if (!context.getSessionId().equals(artifact.getSessionId())
                    || Integer.valueOf(1).equals(artifact.getDeleted())
                    || !"visible".equalsIgnoreCase(artifact.getVisibility())
                    || !"output".equalsIgnoreCase(artifact.getArtifactRole())) {
                throw new ReportStreamException("report_input_forbidden", "指定的输入 Artifact 不属于当前可访问会话");
            }
            Long size = artifact.getFileSize() == null ? artifact.getSize() : artifact.getFileSize();
            if (size == null || size < 0 || size > MAX_SINGLE_ARTIFACT_BYTES || !isSupportedText(artifact)
                    || StringUtils.isAnyBlank(artifact.getStorageType(), artifact.getStorageKey())) {
                throw new ReportStreamException("report_input_unsupported", "输入 Artifact 不是可读取的文本交付文件");
            }
            totalBytes += size;
            if (totalBytes > MAX_TOTAL_ARTIFACT_BYTES) {
                throw new ReportStreamException("report_input_budget_exceeded", "输入 Artifact 总大小超过 Report 读取预算");
            }
            resolved.add(ReportInputArtifactDescriptor.builder()
                    .artifactId(artifact.getArtifactId())
                    .fileName(StringUtils.defaultIfBlank(artifact.getFileName(), artifact.getArtifactName()))
                    .mimeType(artifact.getMimeType())
                    .fileSize(size)
                    .storageType(artifact.getStorageType())
                    .storageKey(artifact.getStorageKey())
                    .build());
        }
        return List.copyOf(resolved);
    }

    private boolean isSupportedText(ArtifactLedgerEntity artifact) {
        String mime = StringUtils.defaultString(artifact.getMimeType()).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String fileName = StringUtils.defaultString(artifact.getFileName()).toLowerCase(Locale.ROOT);
        return TEXT_MIME_TYPES.contains(mime) || TEXT_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }
}
