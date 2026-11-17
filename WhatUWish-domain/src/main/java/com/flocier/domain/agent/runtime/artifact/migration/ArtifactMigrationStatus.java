package com.flocier.domain.agent.runtime.artifact.migration;

public enum ArtifactMigrationStatus {
    READY,
    MIGRATED,
    ROLLED_BACK,
    SKIPPED,
    FAILED
}
