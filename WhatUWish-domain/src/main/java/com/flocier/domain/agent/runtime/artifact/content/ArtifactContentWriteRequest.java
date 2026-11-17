package com.flocier.domain.agent.runtime.artifact.content;

import lombok.Builder;
import lombok.Value;

/** 服务端主动写入 Artifact 内容时使用的内部请求，不属于公开文件 API。 */
@Value
@Builder
public class ArtifactContentWriteRequest {
    String storageType;
    String storageKey;
    byte[] content;
    String mimeType;
}
