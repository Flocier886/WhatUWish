package com.flocier.domain.agent.runtime.artifact;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/** 只负责把 registry 元数据压缩为 LLM 可见摘要，不修改 ledger 事实。 */
@Component
public class ArtifactRuntimeFormatter {
    private static final int MAX_ITEMS_PER_TOOL_CALL = 10;
    private static final int MAX_DELIVERY_DESCRIPTION_LENGTH = 300;
    public static final String ARTIFACT_DELIMITER = "$$$";

    public String appendToolArtifactSummary(String observation, List<ArtifactRuntimeBinding> bindings) {
        String summary = formatToolArtifactSummary(bindings);
        if (StringUtils.isBlank(summary)) {
            return observation;
        }
        return StringUtils.isBlank(observation) ? "关联产物：\n" + summary : observation + "\n\n关联产物：\n" + summary;
    }

    public String formatToolArtifactSummary(List<ArtifactRuntimeBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return "";
        }
        return bindings.stream().filter(ArtifactRuntimeBinding::isVisible).limit(MAX_ITEMS_PER_TOOL_CALL)
                .map(this::formatObservationLine).filter(StringUtils::isNotBlank).collect(java.util.stream.Collectors.joining("\n"));
    }

    public String formatSummaryContext(List<ArtifactRuntimeBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return "";
        }
        String candidates = bindings.stream().filter(ArtifactRuntimeBinding::isDeliverableOutput)
                .map(this::formatDeliveryLine).filter(StringUtils::isNotBlank).collect(java.util.stream.Collectors.joining("\n"));
        if (StringUtils.isBlank(candidates)) {
            return "";
        }
        return "可交付产物候选：\n" + candidates
                + "\n如需交付文件，请在回复正文后使用 " + ARTIFACT_DELIMITER + " 单独列出 artifactKey；只列出需要交付的 key。";
    }

    private String formatObservationLine(ArtifactRuntimeBinding binding) {
        if (binding == null || binding.getDescriptor() == null) {
            return "";
        }
        ArtifactRuntimeDescriptor descriptor = binding.getDescriptor();
        return "- artifactKey: " + binding.artifactKey()
                + " | fileName: " + StringUtils.defaultString(descriptor.getFileName(), descriptor.getName())
                + " | type: " + StringUtils.defaultString(descriptor.getArtifactType(), "file")
                + " | size: " + (descriptor.getFileSize() == null ? "unknown" : descriptor.getFileSize())
                + " | read: artifact_read(artifactId=" + StringUtils.defaultString(descriptor.getArtifactId()) + ")";
    }

    private String formatDeliveryLine(ArtifactRuntimeBinding binding) {
        if (binding == null || binding.getDescriptor() == null) {
            return "";
        }
        ArtifactRuntimeDescriptor descriptor = binding.getDescriptor();
        return formatObservationLine(binding)
                + " | summary: " + StringUtils.abbreviate(
                StringUtils.defaultString(descriptor.getContentSummary()), MAX_DELIVERY_DESCRIPTION_LENGTH);
    }
}
