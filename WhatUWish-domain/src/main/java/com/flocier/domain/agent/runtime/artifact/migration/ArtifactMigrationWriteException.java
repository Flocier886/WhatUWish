package com.flocier.domain.agent.runtime.artifact.migration;

public class ArtifactMigrationWriteException extends RuntimeException {
    public ArtifactMigrationWriteException(String code) {
        super(code);
    }
}
