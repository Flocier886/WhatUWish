package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.service.LedgerQueryService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 解析当前运行或历史账本中已经登记的真实 Artifact 引用。
 *
 * <p>该服务只返回领域内部使用的定位元数据，不读取正文，也不是下载或预览 API。</p>
 */
@Service
@RequiredArgsConstructor
public class ArtifactRuntimeReferenceResolver {

    private final LedgerQueryService ledgerQueryService;

    public ArtifactRuntimeReadReference resolveCurrent(AgentContext context, String artifactKey) {
        if (context == null || StringUtils.isBlank(artifactKey)) {
            return null;
        }

        ArtifactRuntimeBinding binding = context.requireArtifactRuntimeRegistry()
                .findByArtifactKey(artifactKey);
        if (binding == null || !binding.isVisible()) {
            return null;
        }

        return toReference(binding);
    }

    public ArtifactRuntimeReadReference resolveHistorical(String runId, String artifactKey) {
        if (StringUtils.isBlank(runId) || StringUtils.isBlank(artifactKey)) {
            return null;
        }

        List<ArtifactLedgerEntity> artifacts = ledgerQueryService.queryRunView(runId)
                .map(view -> view.getArtifacts())
                .orElse(List.of());
        return artifacts.stream()
                .filter(this::isReadable)
                .filter(item -> artifactKey(item).equals(artifactKey))
                .findFirst()
                .map(this::toReference)
                .orElse(null);
    }

    private boolean isReadable(ArtifactLedgerEntity item) {
        return item != null
                && !Integer.valueOf(1).equals(item.getDeleted())
                && "visible".equalsIgnoreCase(item.getVisibility())
                && !"llm_response".equalsIgnoreCase(item.getArtifactType())
                && !"tool_result".equalsIgnoreCase(item.getArtifactType())
                && !"inline_summary".equalsIgnoreCase(item.getStorageType());
    }

    private ArtifactRuntimeReadReference toReference(ArtifactRuntimeBinding binding) {
        ArtifactRuntimeDescriptor descriptor = binding.getDescriptor();
        return ArtifactRuntimeReadReference.builder()
                .artifactKey(binding.artifactKey())
                .artifactId(descriptor.getArtifactId())
                .storageKey(descriptor.getStorageKey())
                .storageRef(descriptor.getStorageRef())
                .fileName(descriptor.getFileName())
                .mimeType(descriptor.getMimeType())
                .fileSize(descriptor.getFileSize())
                .build();
    }

    private ArtifactRuntimeReadReference toReference(ArtifactLedgerEntity item) {
        return ArtifactRuntimeReadReference.builder()
                .artifactKey(artifactKey(item))
                .artifactId(item.getArtifactId())
                .storageKey(item.getStorageKey())
                .storageRef(item.getStorageRef())
                .fileName(item.getFileName())
                .mimeType(item.getMimeType())
                .fileSize(item.getFileSize())
                .build();
    }

    private String artifactKey(ArtifactLedgerEntity item) {
        return StringUtils.defaultString(item.getToolCallId())
                + "::"
                + StringUtils.defaultString(item.getArtifactId());
    }
}
