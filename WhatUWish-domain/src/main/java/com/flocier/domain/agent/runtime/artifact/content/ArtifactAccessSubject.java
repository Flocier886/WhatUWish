package com.flocier.domain.agent.runtime.artifact.content;

import lombok.Value;

/** 为未来用户、项目或租户授权保留的访问主体。 */
@Value
public class ArtifactAccessSubject {
    String subjectId;
    boolean trustedLocal;

    public static ArtifactAccessSubject localTrustedSubject() {
        return new ArtifactAccessSubject("trusted-local", true);
    }
}
