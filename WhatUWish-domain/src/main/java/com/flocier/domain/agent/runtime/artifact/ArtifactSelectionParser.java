package com.flocier.domain.agent.runtime.artifact;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/** 解析 SummaryAgent 的 $$$ artifactKey 选择段，未知或内部 key 不可交付。 */
@Component
public class ArtifactSelectionParser {
    public ArtifactSelectionResult parse(String response, ArtifactRuntimeRegistry registry) {
        String raw = StringUtils.defaultString(response);
        int delimiterIndex = raw.indexOf(ArtifactRuntimeFormatter.ARTIFACT_DELIMITER);
        String summaryText = delimiterIndex < 0 ? raw : raw.substring(0, delimiterIndex).trim();
        String selectionText = delimiterIndex < 0 ? "" : raw.substring(delimiterIndex + ArtifactRuntimeFormatter.ARTIFACT_DELIMITER.length());
        LinkedHashSet<ArtifactRuntimeBinding> selected = new LinkedHashSet<>();
        for (String key : selectionText.split("[,\\s]+")) {
            ArtifactRuntimeBinding binding = registry == null ? null : registry.findByArtifactKey(key.trim());
            if (binding != null && binding.isDeliverableOutput()) {
                selected.add(binding);
            }
        }
        boolean explicit = !selected.isEmpty();
        List<ArtifactRuntimeBinding> files = explicit
                ? List.copyOf(selected)
                : (registry == null ? List.of() : registry.listDeliverableOutputs());
        return ArtifactSelectionResult.builder()
                .summaryText(summaryText)
                .fileList(files)
                .artifactRefs(files.stream().map(ArtifactRuntimeBinding::artifactKey).toList())
                .explicitSelection(explicit)
                .build();
    }
}
