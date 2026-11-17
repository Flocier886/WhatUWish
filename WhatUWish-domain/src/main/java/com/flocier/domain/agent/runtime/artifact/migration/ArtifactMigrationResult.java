package com.flocier.domain.agent.runtime.artifact.migration;

/** 单个 Artifact 的受控迁移结果；不包含 endpoint、bucket、凭证或对象 URL。 */
public class ArtifactMigrationResult {
    private final ArtifactMigrationOperation operation;
    private final ArtifactMigrationStatus status;
    private final String artifactId;
    private final String runId;
    private final String sourceStorageType;
    private final String targetStorageType;
    private final String targetStorageKey;
    private final long size;
    private final String sha256;
    private final String errorCode;
    private final boolean ledgerUpdated;
    private final boolean orphanObject;

    public ArtifactMigrationResult(ArtifactMigrationOperation operation, ArtifactMigrationStatus status,
                                   String artifactId, String runId, String sourceStorageType,
                                   String targetStorageType, String targetStorageKey, long size, String sha256,
                                   String errorCode, boolean ledgerUpdated, boolean orphanObject) {
        this.operation = operation;
        this.status = status;
        this.artifactId = artifactId;
        this.runId = runId;
        this.sourceStorageType = sourceStorageType;
        this.targetStorageType = targetStorageType;
        this.targetStorageKey = targetStorageKey;
        this.size = size;
        this.sha256 = sha256;
        this.errorCode = errorCode;
        this.ledgerUpdated = ledgerUpdated;
        this.orphanObject = orphanObject;
    }

    public ArtifactMigrationOperation getOperation() {
        return operation;
    }

    public ArtifactMigrationStatus getStatus() {
        return status;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getRunId() {
        return runId;
    }

    public String getSourceStorageType() {
        return sourceStorageType;
    }

    public String getTargetStorageType() {
        return targetStorageType;
    }

    public String getTargetStorageKey() {
        return targetStorageKey;
    }

    public long getSize() {
        return size;
    }

    public String getSha256() {
        return sha256;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isLedgerUpdated() {
        return ledgerUpdated;
    }

    public boolean isOrphanObject() {
        return orphanObject;
    }
}
