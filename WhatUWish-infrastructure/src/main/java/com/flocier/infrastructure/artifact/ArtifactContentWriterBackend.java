package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import java.util.Set;

/** 基础设施侧按显式 storageType 接收 Artifact 内容写入。 */
public interface ArtifactContentWriterBackend {
    Set<String> storageTypes();

    ArtifactStoredContent write(ArtifactContentWriteRequest request);

    /** 删除本后端刚写入且尚未形成有效引用的对象，只用于失败补偿。 */
    void remove(ArtifactContentLocator locator);
}
