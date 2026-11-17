package com.flocier.domain.agent.runtime.artifact.content;

/** 仅用于写入失败补偿，正常软移除不删除被历史 Run 引用的字节。 */
public interface ArtifactContentRemover {
    void remove(ArtifactContentLocator locator);
}
