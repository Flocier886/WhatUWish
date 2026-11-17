package com.flocier.domain.agent.runtime.artifact;

import lombok.Getter;

/** Agent 读取 Artifact 时的受控访问失败。 */
@Getter
public class ArtifactReadAccessException extends RuntimeException {

    private final String errorCode;

    public ArtifactReadAccessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
