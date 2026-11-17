package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 从最终摘要选择协议与真实 artifact ledger 还原交付文件，不猜测普通 tool/LLM 文本。 */
@Component
public class ArtifactReplayResolver {
    public ArtifactReplayResult resolve(String finalText, List<ArtifactLedgerEntity> artifacts) {
        String raw = StringUtils.defaultString(finalText);
        int delimiter = raw.indexOf(ArtifactRuntimeFormatter.ARTIFACT_DELIMITER);
        String summary = delimiter < 0 ? raw : raw.substring(0, delimiter).trim();
        String selection = delimiter < 0 ? "" : raw.substring(delimiter + ArtifactRuntimeFormatter.ARTIFACT_DELIMITER.length());
        Map<String, ArtifactLedgerEntity> candidates = new LinkedHashMap<>();
        for (ArtifactLedgerEntity artifact : artifacts == null ? List.<ArtifactLedgerEntity>of() : artifacts) {
            if (isDeliverable(artifact)) {
                candidates.putIfAbsent(keyOf(artifact), artifact);
            }
        }
        LinkedHashSet<ArtifactLedgerEntity> selected = new LinkedHashSet<>();
        for (String key : selection.split("[,\\s]+")) {
            ArtifactLedgerEntity artifact = candidates.get(key.trim());
            if (artifact != null) {
                selected.add(artifact);
            }
        }
        boolean explicit = !selected.isEmpty();
        List<ArtifactLedgerEntity> files = explicit ? List.copyOf(selected) : List.copyOf(candidates.values());
        return ArtifactReplayResult.builder().summaryText(summary).fileList(files)
                .artifactRefs(files.stream().map(this::keyOf).toList()).explicitSelection(explicit).build();
    }

    private boolean isDeliverable(ArtifactLedgerEntity item) {
        return item != null && !Integer.valueOf(1).equals(item.getDeleted())
                && "visible".equalsIgnoreCase(item.getVisibility())
                && "output".equalsIgnoreCase(item.getArtifactRole())
                && !"llm_response".equals(item.getArtifactType())
                && !"tool_result".equals(item.getArtifactType())
                && !"inline_summary".equals(item.getStorageType());
    }

    private String keyOf(ArtifactLedgerEntity item) {
        return StringUtils.defaultString(item.getToolCallId()) + "::" + StringUtils.defaultString(item.getArtifactId());
    }
}
