package com.flocier.domain.agent.runtime.artifact.content;

/** 面向用户内容访问的受控状态，不包含底层存储原因。 */
public enum ArtifactAccessState {
    AVAILABLE,
    UNAVAILABLE,
    FORBIDDEN,
    NOT_FOUND
}
