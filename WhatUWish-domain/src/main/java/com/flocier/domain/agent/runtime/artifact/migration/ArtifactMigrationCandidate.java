package com.flocier.domain.agent.runtime.artifact.migration;

/** 显式迁移命令使用的内部候选，不属于公开 Artifact DTO。 */
public class ArtifactMigrationCandidate {
    private final String artifactId;
    private final String runId;
    private final String sourceStorageType;
    private final String sourceStorageKey;
    private final String targetStorageType;
    private final String targetStorageKey;
    private final Long ledgerSize;
    private final String ledgerHash;
    private final boolean eligible;
    private final String reasonCode;

    public ArtifactMigrationCandidate(String artifactId, String runId, String sourceStorageType,
                                      String sourceStorageKey, String targetStorageType, String targetStorageKey,
                                      Long ledgerSize, String ledgerHash, boolean eligible, String reasonCode) {
        this.artifactId = artifactId;
        this.runId = runId;
        this.sourceStorageType = sourceStorageType;
        this.sourceStorageKey = sourceStorageKey;
        this.targetStorageType = targetStorageType;
        this.targetStorageKey = targetStorageKey;
        this.ledgerSize = ledgerSize;
        this.ledgerHash = ledgerHash;
        this.eligible = eligible;
        this.reasonCode = reasonCode;
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

    public String getSourceStorageKey() {
        return sourceStorageKey;
    }

    public String getTargetStorageType() {
        return targetStorageType;
    }

    public String getTargetStorageKey() {
        return targetStorageKey;
    }

    public Long getLedgerSize() {
        return ledgerSize;
    }

    public String getLedgerHash() {
        return ledgerHash;
    }

    public boolean isEligible() {
        return eligible;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
