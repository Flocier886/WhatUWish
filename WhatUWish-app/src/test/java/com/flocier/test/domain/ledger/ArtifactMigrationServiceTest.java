package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationResult;
import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationService;
import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationStatus;
import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationWriter;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.infrastructure.artifact.ArtifactContentStoreBackend;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArtifactMigrationServiceTest {

    @Test
    public void shouldDryRunWithoutUploadOrLedgerMutation() {
        Fixture fixture = fixture(false);

        ArtifactMigrationResult result = fixture.service.dryRun("artifact-1");

        assertEquals(ArtifactMigrationStatus.READY, result.getStatus());
        assertEquals("project/v1/artifacts/artifact-1/content", result.getTargetStorageKey());
        assertEquals(fixture.content.length, result.getSize());
        assertEquals(0, fixture.writer.uploads.get());
        assertEquals(ArtifactStorageType.LOCAL,
                fixture.repository.findByArtifactId("artifact-1").orElseThrow().getStorageType());
        assertTrue(fixture.object.objects.isEmpty());
    }

    @Test
    public void shouldMigrateRollbackAndMigrateAgainWithoutDeletingEitherCopy() {
        Fixture fixture = fixture(false);

        ArtifactMigrationResult migrated = fixture.service.migrate("artifact-1");
        ArtifactLedgerEntity afterMigrate = fixture.repository.findByArtifactId("artifact-1").orElseThrow();

        assertEquals(ArtifactMigrationStatus.MIGRATED, migrated.getStatus());
        assertEquals(ArtifactStorageType.S3, afterMigrate.getStorageType());
        assertEquals("local/artifact-1.md", afterMigrate.getStorageRef());
        assertTrue(fixture.local.objects.containsKey("local/artifact-1.md"));
        assertTrue(fixture.object.objects.containsKey(afterMigrate.getStorageKey()));

        ArtifactMigrationResult rolledBack = fixture.service.rollback("artifact-1");
        ArtifactLedgerEntity afterRollback = fixture.repository.findByArtifactId("artifact-1").orElseThrow();

        assertEquals(ArtifactMigrationStatus.ROLLED_BACK, rolledBack.getStatus());
        assertEquals(ArtifactStorageType.LOCAL, afterRollback.getStorageType());
        assertEquals("local/artifact-1.md", afterRollback.getStorageKey());
        assertTrue(fixture.object.objects.containsKey("project/v1/artifacts/artifact-1/content"));

        assertEquals(ArtifactMigrationStatus.MIGRATED, fixture.service.migrate("artifact-1").getStatus());
        assertEquals(2, fixture.writer.uploads.get());
    }

    @Test
    public void shouldKeepLedgerLocalWhenTargetValidationFails() {
        Fixture fixture = fixture(false);
        fixture.writer.corruptUpload = true;

        ArtifactMigrationResult result = fixture.service.migrate("artifact-1");

        assertEquals(ArtifactMigrationStatus.FAILED, result.getStatus());
        assertEquals("target_validation_failed", result.getErrorCode());
        assertTrue(result.isOrphanObject());
        assertFalse(result.isLedgerUpdated());
        assertEquals(ArtifactStorageType.LOCAL,
                fixture.repository.findByArtifactId("artifact-1").orElseThrow().getStorageType());
    }

    @Test
    public void shouldReportOrphanOnConflictAndCompleteIdempotentRetry() {
        Fixture fixture = fixture(true);

        ArtifactMigrationResult first = fixture.service.migrate("artifact-1");
        ArtifactMigrationResult second = fixture.service.migrate("artifact-1");

        assertEquals(ArtifactMigrationStatus.FAILED, first.getStatus());
        assertEquals("ledger_locator_conflict", first.getErrorCode());
        assertTrue(first.isOrphanObject());
        assertEquals(ArtifactMigrationStatus.MIGRATED, second.getStatus());
        assertEquals(2, fixture.writer.uploads.get());
        assertEquals(1, fixture.object.objects.size());
    }

    @Test
    public void shouldRejectRollbackWhenRetainedLocalHashChanged() {
        Fixture fixture = fixture(false);
        assertEquals(ArtifactMigrationStatus.MIGRATED, fixture.service.migrate("artifact-1").getStatus());
        fixture.local.objects.put("local/artifact-1.md", "changed".getBytes());

        ArtifactMigrationResult result = fixture.service.rollback("artifact-1");

        assertEquals(ArtifactMigrationStatus.FAILED, result.getStatus());
        assertEquals(ArtifactStorageType.S3,
                fixture.repository.findByArtifactId("artifact-1").orElseThrow().getStorageType());
    }

    @Test
    public void shouldRestoreS3LocatorWhenRollbackPostCheckFails() {
        Fixture fixture = fixture(false);
        assertEquals(ArtifactMigrationStatus.MIGRATED, fixture.service.migrate("artifact-1").getStatus());
        fixture.local.failInspectAfter(1);

        ArtifactMigrationResult result = fixture.service.rollback("artifact-1");

        assertEquals(ArtifactMigrationStatus.FAILED, result.getStatus());
        assertEquals("rollback_post_check_failed_reverted", result.getErrorCode());
        assertFalse(result.isLedgerUpdated());
        assertEquals(ArtifactStorageType.S3,
                fixture.repository.findByArtifactId("artifact-1").orElseThrow().getStorageType());
    }

    @Test
    public void shouldReportRollbackCompensationConflictWithFinalLocalLocator() {
        Fixture fixture = fixture(false);
        assertEquals(ArtifactMigrationStatus.MIGRATED, fixture.service.migrate("artifact-1").getStatus());
        fixture.local.failInspectAfter(1);
        fixture.repository.failAfterUpdates(1);

        ArtifactMigrationResult result = fixture.service.rollback("artifact-1");

        assertEquals(ArtifactMigrationStatus.FAILED, result.getStatus());
        assertEquals("rollback_post_check_failed_compensation_conflict", result.getErrorCode());
        assertTrue(result.isLedgerUpdated());
        assertEquals(ArtifactStorageType.LOCAL,
                fixture.repository.findByArtifactId("artifact-1").orElseThrow().getStorageType());
    }

    @Test
    public void shouldFilterInvalidCandidatesAndContinuePartialBatch() {
        Fixture fixture = fixture(false);
        ArtifactLedgerEntity internal = entity("artifact-internal", "local/internal.md", "internal" , "output");
        fixture.repository.save(internal);
        fixture.local.objects.put("local/internal.md", "internal".getBytes());
        ArtifactLedgerEntity missing = entity("artifact-missing", "local/missing.md", "visible", "output");
        fixture.repository.save(missing);

        assertEquals(0, fixture.service.candidates(10).stream()
                .filter(candidate -> "artifact-internal".equals(candidate.getArtifactId())).count());
        assertEquals(ArtifactMigrationStatus.SKIPPED,
                fixture.service.dryRun("artifact-internal").getStatus());

        List<ArtifactMigrationResult> results = fixture.service.migrateCandidates(10);

        assertTrue(results.stream().anyMatch(result -> "artifact-1".equals(result.getArtifactId())
                && result.getStatus() == ArtifactMigrationStatus.MIGRATED));
        assertTrue(results.stream().anyMatch(result -> "artifact-missing".equals(result.getArtifactId())
                && result.getStatus() != ArtifactMigrationStatus.MIGRATED));
    }

    private Fixture fixture(boolean failFirstUpdate) {
        byte[] content = "migration body".getBytes();
        ConflictRepository repository = new ConflictRepository(failFirstUpdate);
        MemoryBackend local = new MemoryBackend(ArtifactStorageType.LOCAL);
        MemoryBackend object = new MemoryBackend(ArtifactStorageType.S3);
        local.objects.put("local/artifact-1.md", content);
        repository.save(entity("artifact-1", "local/artifact-1.md", "visible", "output"));
        MemoryWriter writer = new MemoryWriter(object);
        RoutingArtifactContentStore store = new RoutingArtifactContentStore(List.of(local, object));
        return new Fixture(repository, local, object, writer,
                new ArtifactMigrationService(repository, store, writer), content);
    }

    private ArtifactLedgerEntity entity(String artifactId, String key, String visibility, String role) {
        byte[] content = "migration body".getBytes();
        return ArtifactLedgerEntity.builder()
                .artifactId(artifactId).runId("run-1").sessionId("session-1")
                .artifactName("report.md").fileName("report.md").artifactType("report")
                .artifactRole(role).visibility(visibility).storageType(ArtifactStorageType.LOCAL)
                .storageKey(key).storageRef(key).mimeType("text/markdown")
                .fileSize((long) content.length).size((long) content.length)
                .fileHash(DigestUtils.sha256Hex(content)).deleted(0).createdAt(Instant.now()).build();
    }

    private static class Fixture {
        private final ConflictRepository repository;
        private final MemoryBackend local;
        private final MemoryBackend object;
        private final MemoryWriter writer;
        private final ArtifactMigrationService service;
        private final byte[] content;

        private Fixture(ConflictRepository repository, MemoryBackend local, MemoryBackend object,
                        MemoryWriter writer, ArtifactMigrationService service, byte[] content) {
            this.repository = repository;
            this.local = local;
            this.object = object;
            this.writer = writer;
            this.service = service;
            this.content = content;
        }
    }

    private static class ConflictRepository extends InMemoryArtifactLedgerRepository {
        private int successfulUpdatesBeforeFailure;

        private ConflictRepository(boolean failNextUpdate) {
            this.successfulUpdatesBeforeFailure = failNextUpdate ? 0 : -1;
        }

        private void failAfterUpdates(int successfulUpdates) {
            this.successfulUpdatesBeforeFailure = Math.max(0, successfulUpdates);
        }

        @Override
        public synchronized boolean updateStorageLocator(String artifactId, String expectedStorageType,
                                                         String expectedStorageKey, String newStorageType,
                                                         String newStorageKey, String newStorageRef) {
            if (successfulUpdatesBeforeFailure == 0) {
                successfulUpdatesBeforeFailure = -1;
                return false;
            }
            if (successfulUpdatesBeforeFailure > 0) {
                successfulUpdatesBeforeFailure--;
            }
            return super.updateStorageLocator(artifactId, expectedStorageType, expectedStorageKey,
                    newStorageType, newStorageKey, newStorageRef);
        }
    }

    private static class MemoryWriter implements ArtifactMigrationWriter {
        private final MemoryBackend target;
        private final AtomicInteger uploads = new AtomicInteger();
        private boolean corruptUpload;

        private MemoryWriter(MemoryBackend target) {
            this.target = target;
        }

        @Override
        public String targetKey(String artifactId) {
            return "project/v1/artifacts/" + artifactId + "/content";
        }

        @Override
        public void upload(String artifactId, String targetKey, String mimeType, String sha256,
                           long contentLength, InputStream content) {
            uploads.incrementAndGet();
            try {
                byte[] bytes = content.readAllBytes();
                target.objects.put(targetKey, corruptUpload ? "corrupt".getBytes() : bytes);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static class MemoryBackend implements ArtifactContentStoreBackend {
        private final String storageType;
        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        private int successfulInspectsBeforeFailure = -1;

        private MemoryBackend(String storageType) {
            this.storageType = storageType;
        }

        private void failInspectAfter(int successfulInspects) {
            this.successfulInspectsBeforeFailure = Math.max(0, successfulInspects);
        }

        @Override
        public Set<String> storageTypes() {
            return Set.of(storageType);
        }

        @Override
        public ArtifactStoredContent inspect(ArtifactContentLocator locator) {
            if (successfulInspectsBeforeFailure == 0) {
                successfulInspectsBeforeFailure = -1;
                throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
            }
            if (successfulInspectsBeforeFailure > 0) {
                successfulInspectsBeforeFailure--;
            }
            byte[] content = objects.get(locator.getStorageKey());
            if (content == null) {
                throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
            }
            return new ArtifactStoredContent(content.length);
        }

        @Override
        public InputStream openDownload(ArtifactContentLocator locator) {
            byte[] content = objects.get(locator.getStorageKey());
            if (content == null) {
                throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
            }
            return new ByteArrayInputStream(content);
        }

        @Override
        public byte[] readPreview(ArtifactContentLocator locator, int maxBytes) {
            byte[] content = objects.get(locator.getStorageKey());
            if (content == null || content.length > maxBytes) {
                throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
            }
            return content.clone();
        }
    }
}
