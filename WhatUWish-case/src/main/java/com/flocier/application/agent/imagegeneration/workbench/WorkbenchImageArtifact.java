package com.flocier.application.agent.imagegeneration.workbench;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import lombok.Builder;
import lombok.Value;

/** 工作台输出图片的受控文件身份与能力，不包含对象 key、URL 或二进制。 */
@Value
@Builder
public class WorkbenchImageArtifact {
    String artifactId;
    String name;
    String mimeType;
    Long size;
    String accessState;
    ArtifactPreviewDescriptor preview;
    boolean previewSupported;
    boolean downloadSupported;
}
