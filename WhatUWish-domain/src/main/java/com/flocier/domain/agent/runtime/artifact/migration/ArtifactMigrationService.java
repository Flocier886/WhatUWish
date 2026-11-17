package com.flocier.domain.agent.runtime.artifact.migration;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 显式 dry-run/migrate/rollback 编排；普通启动、Run 和 Replay 不调用本服务。 */
public class ArtifactMigrationService {
    private static final int MAX_BATCH_SIZE = 100;
    private final IArtifactLedgerRepository repository;
    private final ArtifactContentStore contentStore;
    private final ArtifactMigrationWriter writer;

    public ArtifactMigrationService(IArtifactLedgerRepository repository, ArtifactContentStore contentStore,
                                    ArtifactMigrationWriter writer) {
        this.repository = repository;
        this.contentStore = contentStore;
        this.writer = writer;
    }

    public List<ArtifactMigrationCandidate> candidates(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_BATCH_SIZE));
        return repository.findMigrationCandidates(safeLimit).stream().map(this::candidate).toList();
    }

    public List<ArtifactMigrationResult> dryRunCandidates(int limit) {
        List<ArtifactMigrationResult> results = new ArrayList<>();
        for (ArtifactMigrationCandidate candidate : candidates(limit)) {
            results.add(dryRun(candidate.getArtifactId()));
        }
        return results;
    }

    public List<ArtifactMigrationResult> migrateCandidates(int limit) {
        List<ArtifactMigrationResult> results = new ArrayList<>();
        for (ArtifactMigrationCandidate candidate : candidates(limit)) {
            results.add(migrate(candidate.getArtifactId()));
        }
        return results;
    }

    public ArtifactMigrationResult dryRun(String artifactId) {
        ArtifactLedgerEntity entity = repository.findByArtifactId(artifactId).orElse(null);
        if (entity == null) {
            return missingResult(ArtifactMigrationOperation.DRY_RUN, ArtifactMigrationStatus.SKIPPED,
                    artifactId, 0, null, "artifact_not_found", false, false);
        }
        ArtifactMigrationCandidate candidate = candidate(entity);
        if (!candidate.isEligible()) {
            return result(ArtifactMigrationOperation.DRY_RUN, ArtifactMigrationStatus.SKIPPED, entity,
                    candidate.getTargetStorageKey(), 0, null, candidate.getReasonCode(), false, false);
        }
        try {
            ContentDigest source = sourceDigest(entity, candidate.getSourceStorageKey());
            String sourceError = sourceValidationError(entity, source);
            if (sourceError != null) {
                return result(ArtifactMigrationOperation.DRY_RUN, ArtifactMigrationStatus.FAILED, entity,
                        candidate.getTargetStorageKey(), source.size, source.sha256, sourceError, false, false);
            }
            return result(ArtifactMigrationOperation.DRY_RUN, ArtifactMigrationStatus.READY, entity,
                    candidate.getTargetStorageKey(), source.size, source.sha256, null, false, false);
        } catch (RuntimeException exception) {
            return result(ArtifactMigrationOperation.DRY_RUN, ArtifactMigrationStatus.SKIPPED, entity,
                    candidate.getTargetStorageKey(), 0, null, "source_unavailable", false, false);
        }
    }

    public ArtifactMigrationResult migrate(String artifactId) {
        ArtifactMigrationResult prepared = dryRun(artifactId);
        if (prepared.getStatus() != ArtifactMigrationStatus.READY) {
            return copyForOperation(prepared, ArtifactMigrationOperation.MIGRATE);
        }
        ArtifactLedgerEntity entity = repository.findByArtifactId(artifactId).orElse(null);
        if (entity == null) {
            return missingResult(ArtifactMigrationOperation.MIGRATE, ArtifactMigrationStatus.SKIPPED,
                    artifactId, 0, null, "artifact_not_found", false, false);
        }
        String localKey = entity.getStorageKey();
        try (InputStream source = contentStore.openDownload(localLocator(localKey))) {
            writer.upload(entity.getArtifactId(), prepared.getTargetStorageKey(), entity.getMimeType(),
                    prepared.getSha256(), prepared.getSize(), source);
        } catch (IOException | RuntimeException exception) {
            return result(ArtifactMigrationOperation.MIGRATE, ArtifactMigrationStatus.FAILED, entity,
                    prepared.getTargetStorageKey(), prepared.getSize(), prepared.getSha256(),
                    "target_upload_failed", false, true);
        }

        if (!targetMatches(prepared)) {
            return result(ArtifactMigrationOperation.MIGRATE, ArtifactMigrationStatus.FAILED, entity,
                    prepared.getTargetStorageKey(), prepared.getSize(), prepared.getSha256(),
                    "target_validation_failed", false, true);
        }

        boolean updated = repository.updateStorageLocator(entity.getArtifactId(), ArtifactStorageType.LOCAL, localKey,
                ArtifactStorageType.S3, prepared.getTargetStorageKey(), localKey);
        if (!updated) {
            return result(ArtifactMigrationOperation.MIGRATE, ArtifactMigrationStatus.FAILED, entity,
                    prepared.getTargetStorageKey(), prepared.getSize(), prepared.getSha256(),
                    "ledger_locator_conflict", false, true);
        }
        try {
            contentStore.inspect(objectLocator(prepared.getTargetStorageKey()));
        } catch (RuntimeException exception) {
            repository.updateStorageLocator(entity.getArtifactId(), ArtifactStorageType.S3,
                    prepared.getTargetStorageKey(), ArtifactStorageType.LOCAL, localKey, localKey);
            return result(ArtifactMigrationOperation.MIGRATE, ArtifactMigrationStatus.FAILED, entity,
                    prepared.getTargetStorageKey(), prepared.getSize(), prepared.getSha256(),
                    "post_switch_check_failed", false, true);
        }
        return result(ArtifactMigrationOperation.MIGRATE, ArtifactMigrationStatus.MIGRATED, entity,
                prepared.getTargetStorageKey(), prepared.getSize(), prepared.getSha256(), null, true, false);
    }

    public ArtifactMigrationResult rollback(String artifactId) {
        ArtifactLedgerEntity entity = repository.findByArtifactId(artifactId).orElse(null);
        if (entity == null) {
            return missingResult(ArtifactMigrationOperation.ROLLBACK, ArtifactMigrationStatus.SKIPPED,
                    artifactId, 0, null, "artifact_not_found", false, false);
        }
        if (!ArtifactStorageType.S3.equals(entity.getStorageType()) || blank(entity.getStorageKey())
                || blank(entity.getStorageRef()) || Integer.valueOf(1).equals(entity.getDeleted())) {
            return result(ArtifactMigrationOperation.ROLLBACK, ArtifactMigrationStatus.SKIPPED, entity,
                    entity.getStorageKey(), 0, null, "rollback_source_missing", false, false);
        }
        String localKey = entity.getStorageRef();
        ContentDigest source;
        try {
            source = sourceDigest(entity, localKey);
        } catch (RuntimeException exception) {
            return result(ArtifactMigrationOperation.ROLLBACK, ArtifactMigrationStatus.FAILED, entity,
                    localKey, 0, null, "rollback_source_unavailable", false, false);
        }
        String sourceError = sourceValidationError(entity, source);
        if (sourceError != null) {
            return result(ArtifactMigrationOperation.ROLLBACK, ArtifactMigrationStatus.FAILED, entity,
                    localKey, source.size, source.sha256, "rollback_" + sourceError, false, false);
        }
        boolean updated = repository.updateStorageLocator(entity.getArtifactId(), ArtifactStorageType.S3,
                entity.getStorageKey(), ArtifactStorageType.LOCAL, localKey, localKey);
        if (!updated) {
            return result(ArtifactMigrationOperation.ROLLBACK, ArtifactMigrationStatus.FAILED, entity,
                    localKey, source.size, source.sha256, "ledger_locator_conflict", false, false);
        }
        try {
            contentStore.inspect(localLocator(localKey));
        } catch (RuntimeException exception) {
            boolean restored = repository.updateStorageLocator(entity.getArtifactId(), ArtifactStorageType.LOCAL,
                    localKey, ArtifactStorageType.S3, entity.getStorageKey(), localKey);
            return result(ArtifactMigrationOperation.ROLLBACK, ArtifactMigrationStatus.FAILED, entity,
                    localKey, source.size, source.sha256,
                    restored ? "rollback_post_check_failed_reverted"
                            : "rollback_post_check_failed_compensation_conflict",
                    !restored, false);
        }
        return result(ArtifactMigrationOperation.ROLLBACK, ArtifactMigrationStatus.ROLLED_BACK, entity,
                localKey, source.size, source.sha256, null, true, false);
    }

    private ArtifactMigrationCandidate candidate(ArtifactLedgerEntity entity) {
        String reason = candidateReason(entity);
        String targetKey;
        try {
            targetKey = entity == null || blank(entity.getArtifactId()) ? null : writer.targetKey(entity.getArtifactId());
        } catch (RuntimeException exception) {
            targetKey = null;
            reason = "invalid_artifact_id";
        }
        Long size = entity == null ? null : (entity.getFileSize() == null ? entity.getSize() : entity.getFileSize());
        return new ArtifactMigrationCandidate(
                entity == null ? null : entity.getArtifactId(), entity == null ? null : entity.getRunId(),
                entity == null ? null : entity.getStorageType(), entity == null ? null : entity.getStorageKey(),
                ArtifactStorageType.S3, targetKey, size, entity == null ? null : entity.getFileHash(),
                reason == null, reason);
    }

    private String candidateReason(ArtifactLedgerEntity entity) {
        if (entity == null) {
            return "artifact_not_found";
        }
        if (Integer.valueOf(1).equals(entity.getDeleted())) {
            return "artifact_deleted";
        }
        if (!"visible".equalsIgnoreCase(entity.getVisibility())) {
            return "artifact_not_visible";
        }
        if (!"output".equalsIgnoreCase(entity.getArtifactRole())) {
            return "artifact_not_output";
        }
        if (!ArtifactStorageType.LOCAL.equals(entity.getStorageType())) {
            return "artifact_not_local";
        }
        if (blank(entity.getStorageKey())) {
            return "source_locator_missing";
        }
        return null;
    }

    private ContentDigest sourceDigest(ArtifactLedgerEntity entity, String localKey) {
        ArtifactContentLocator locator = localLocator(localKey);
        long inspectedSize = contentStore.inspect(locator).getSize();
        try (InputStream input = contentStore.openDownload(locator)) {
            ContentDigest digest = digest(input);
            if (digest.size != inspectedSize) {
                throw new IllegalStateException("source_size_changed");
            }
            return digest;
        } catch (IOException exception) {
            throw new IllegalStateException("source_unavailable");
        }
    }

    private boolean targetMatches(ArtifactMigrationResult prepared) {
        ArtifactContentLocator locator = objectLocator(prepared.getTargetStorageKey());
        try {
            if (contentStore.inspect(locator).getSize() != prepared.getSize()) {
                return false;
            }
            try (InputStream input = contentStore.openDownload(locator)) {
                ContentDigest digest = digest(input);
                return digest.size == prepared.getSize() && prepared.getSha256().equals(digest.sha256);
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private String sourceValidationError(ArtifactLedgerEntity entity, ContentDigest source) {
        Long ledgerSize = entity.getFileSize() == null ? entity.getSize() : entity.getFileSize();
        if (ledgerSize != null && ledgerSize != source.size) {
            return "source_size_mismatch";
        }
        if (!blank(entity.getFileHash()) && !entity.getFileHash().equalsIgnoreCase(source.sha256)) {
            return "source_hash_mismatch";
        }
        return null;
    }

    private ContentDigest digest(InputStream input) throws IOException {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (DigestInputStream digestInput = new DigestInputStream(input, messageDigest)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = digestInput.read(buffer)) >= 0) {
                    size += read;
                }
            }
            return new ContentDigest(size, HexFormat.of().formatHex(messageDigest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }

    private ArtifactContentLocator localLocator(String key) {
        return new ArtifactContentLocator(ArtifactStorageType.LOCAL, key, key);
    }

    private ArtifactContentLocator objectLocator(String key) {
        return new ArtifactContentLocator(ArtifactStorageType.S3, key, null);
    }

    private ArtifactMigrationResult copyForOperation(ArtifactMigrationResult source,
                                                     ArtifactMigrationOperation operation) {
        return new ArtifactMigrationResult(operation, source.getStatus(), source.getArtifactId(), source.getRunId(),
                source.getSourceStorageType(), source.getTargetStorageType(), source.getTargetStorageKey(),
                source.getSize(), source.getSha256(), source.getErrorCode(), source.isLedgerUpdated(),
                source.isOrphanObject());
    }

    private ArtifactMigrationResult result(ArtifactMigrationOperation operation, ArtifactMigrationStatus status,
                                           ArtifactLedgerEntity entity, String targetKey, long size, String sha256,
                                           String errorCode, boolean ledgerUpdated, boolean orphanObject) {
        String sourceStorageType = entity == null ? null : entity.getStorageType();
        if (status == ArtifactMigrationStatus.MIGRATED) {
            sourceStorageType = ArtifactStorageType.LOCAL;
        } else if (status == ArtifactMigrationStatus.ROLLED_BACK) {
            sourceStorageType = ArtifactStorageType.S3;
        }
        return new ArtifactMigrationResult(operation, status,
                entity == null ? null : entity.getArtifactId(), entity == null ? null : entity.getRunId(),
                sourceStorageType,
                operation == ArtifactMigrationOperation.ROLLBACK ? ArtifactStorageType.LOCAL : ArtifactStorageType.S3,
                targetKey, size, sha256, errorCode, ledgerUpdated, orphanObject);
    }

    private ArtifactMigrationResult missingResult(ArtifactMigrationOperation operation,
                                                  ArtifactMigrationStatus status, String artifactId,
                                                  long size, String sha256, String errorCode,
                                                  boolean ledgerUpdated, boolean orphanObject) {
        return new ArtifactMigrationResult(operation, status, artifactId, null, null,
                operation == ArtifactMigrationOperation.ROLLBACK ? ArtifactStorageType.LOCAL : ArtifactStorageType.S3,
                null, size, sha256, errorCode, ledgerUpdated, orphanObject);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static class ContentDigest {
        private final long size;
        private final String sha256;

        private ContentDigest(long size, String sha256) {
            this.size = size;
            this.sha256 = sha256;
        }
    }
}
