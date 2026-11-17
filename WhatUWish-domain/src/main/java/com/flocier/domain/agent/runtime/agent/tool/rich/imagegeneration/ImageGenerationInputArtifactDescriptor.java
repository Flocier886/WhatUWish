package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import lombok.Builder;
import lombok.Value;

/** Java 授权后才允许进入 Python 私有请求的图片定位信息。 */
@Value
@Builder
public class ImageGenerationInputArtifactDescriptor {
    String artifactId;
    String fileName;
    String mimeType;
    Long fileSize;
    String storageType;
    String storageKey;
    String role;
}
