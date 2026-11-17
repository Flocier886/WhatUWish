package com.flocier.domain.agent.runtime.artifact.content;

/** HTTP 层可映射的用户内容访问失败，不携带内部定位信息。 */
public class ArtifactContentAccessException extends RuntimeException {
    private final ArtifactAccessState state;

    public ArtifactContentAccessException(ArtifactAccessState state) {
        super(state.name());
        this.state = state;
    }

    public ArtifactAccessState getState() {
        return state;
    }
}
