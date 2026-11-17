package com.flocier.domain.agent.runtime.artifact;

import lombok.Builder;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;

/** registry 中的单个“工具来源 -> 真实 artifact”绑定。 */
@Value
@Builder
public class ArtifactRuntimeBinding {
    ArtifactRuntimeSource source;
    ArtifactRuntimeDescriptor descriptor;

    public String artifactKey() {
        if (source == null || descriptor == null) {
            return "";
        }
        return StringUtils.defaultString(source.getToolCallId()) + "::" + StringUtils.defaultString(descriptor.getArtifactId());
    }

    public boolean isVisible() {
        return descriptor != null && !"internal".equalsIgnoreCase(descriptor.getVisibility());
    }

    public boolean isDeliverableOutput() {
        return isVisible() && descriptor != null && "output".equalsIgnoreCase(descriptor.getArtifactRole());
    }
}
