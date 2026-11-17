package com.flocier.domain.agent.runtime.artifact;

import lombok.Builder;
import lombok.Value;

/** 已经完成权限/删除状态过滤后的 artifact 读取引用，不包含文件字节。 */
@Value
@Builder
public class ArtifactRuntimeReadReference {
    String artifactKey;
    String artifactId;
    String storageKey;
    String storageRef;
    String fileName;
    String mimeType;
    Long fileSize;
}
