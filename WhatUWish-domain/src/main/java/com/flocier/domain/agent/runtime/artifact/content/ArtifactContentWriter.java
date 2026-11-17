package com.flocier.domain.agent.runtime.artifact.content;

/** Artifact 物理内容的受控写入 Port，与用户预览/下载读取 Port 分离。 */
public interface ArtifactContentWriter {
    ArtifactStoredContent write(ArtifactContentWriteRequest request);
}
