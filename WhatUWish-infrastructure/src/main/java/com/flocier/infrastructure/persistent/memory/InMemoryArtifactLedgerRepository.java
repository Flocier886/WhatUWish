package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryArtifactLedgerRepository implements IArtifactLedgerRepository {

    private final CopyOnWriteArrayList<ArtifactLedgerEntity> storage = new CopyOnWriteArrayList<>();

    @Override
    public void save(ArtifactLedgerEntity entity) {
        storage.add(entity);
    }

    @Override
    public Optional<ArtifactLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return storage.stream().filter(item -> idempotencyKey.equals(item.getIdempotencyKey())).findFirst();
    }

    @Override
    public List<ArtifactLedgerEntity> findByRunId(String runId) {
        return storage.stream()
                .filter(item -> runId == null || runId.equals(item.getRunId()))
                .filter(this::activeReusableArtifact)
                .sorted(Comparator.comparing(ArtifactLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public List<ArtifactLedgerEntity> findByRunIds(List<String> runIds) {
        return runIds == null || runIds.isEmpty() ? List.of() : storage.stream()
                .filter(item -> runIds.contains(item.getRunId()))
                .filter(this::activeReusableArtifact)
                .sorted(Comparator.comparing(ArtifactLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public Optional<ArtifactLedgerEntity> findByArtifactId(String artifactId) {
        return storage.stream().filter(item -> artifactId != null && artifactId.equals(item.getArtifactId())).findFirst();
    }

    @Override
    public List<ArtifactLedgerEntity> findMigrationCandidates(int limit) {
        return storage.stream().filter(this::migrationCandidate)
                .sorted(Comparator.comparing(ArtifactLedgerEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(Math.max(1, Math.min(limit, 100))).toList();
    }

    @Override
    public synchronized boolean updateStorageLocator(String artifactId, String expectedStorageType,
                                                     String expectedStorageKey, String newStorageType,
                                                     String newStorageKey, String newStorageRef) {
        Optional<ArtifactLedgerEntity> current = findByArtifactId(artifactId);
        if (current.isEmpty() || !equals(expectedStorageType, current.get().getStorageType())
                || !equals(expectedStorageKey, current.get().getStorageKey())
                || Integer.valueOf(1).equals(current.get().getDeleted())) {
            return false;
        }
        ArtifactLedgerEntity entity = current.get();
        entity.setStorageType(newStorageType);
        entity.setStorageKey(newStorageKey);
        entity.setStorageRef(newStorageRef);
        entity.setUpdatedAt(java.time.Instant.now());
        return true;
    }

    @Override
    public List<ArtifactLedgerEntity> findByRunIdsIncludingDeleted(List<String> runIds) {
        return runIds == null || runIds.isEmpty() ? List.of() : storage.stream()
                .filter(item -> runIds.contains(item.getRunId()))
                .filter(this::realArtifact)
                .sorted(Comparator.comparing(ArtifactLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public List<ArtifactLedgerEntity> findByTraceId(String traceId, Integer limit) {
        return storage.stream()
                .filter(item -> traceId == null || traceId.equals(item.getTraceId()))
                .filter(this::activeReusableArtifact)
                .sorted(Comparator.comparing(ArtifactLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(limit == null || limit <= 0 ? 100 : limit)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public List<ArtifactLedgerEntity> findByToolInvocationId(String toolInvocationId) {
        return storage.stream()
                .filter(item -> toolInvocationId == null || toolInvocationId.equals(item.getToolInvocationId()))
                .filter(this::activeReusableArtifact)
                .sorted(Comparator.comparing(ArtifactLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public List<ArtifactLedgerEntity> findByRunIdAndToolCallId(String runId, String toolCallId) {
        return storage.stream()
                .filter(item -> runId == null || runId.equals(item.getRunId()))
                .filter(item -> toolCallId == null || toolCallId.equals(item.getToolCallId()))
                .filter(this::activeReusableArtifact)
                .sorted(Comparator.comparing(ArtifactLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public List<ArtifactLedgerEntity> findByRequestIdAndToolCallId(String requestId, String toolCallId) {
        return storage.stream()
                .filter(item -> requestId == null || requestId.equals(item.getRequestId()))
                .filter(item -> toolCallId == null || toolCallId.equals(item.getToolCallId()))
                .filter(this::activeReusableArtifact)
                .sorted(Comparator.comparing(ArtifactLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private boolean activeReusableArtifact(ArtifactLedgerEntity item) {
        if (item == null || Integer.valueOf(1).equals(item.getDeleted())) {
            return false;
        }
        return realArtifact(item);
    }

    private boolean realArtifact(ArtifactLedgerEntity item) {
        if (item == null) {
            return false;
        }
        return !equalsIgnoreCase("llm_response", item.getArtifactType())
                && !equalsIgnoreCase("tool_result", item.getArtifactType())
                && !equalsIgnoreCase("inline_summary", item.getStorageType());
    }

    private boolean equalsIgnoreCase(String expected, String actual) {
        return expected.equalsIgnoreCase(actual == null ? "" : actual);
    }

    private boolean migrationCandidate(ArtifactLedgerEntity item) {
        return activeReusableArtifact(item)
                && equalsIgnoreCase("local_artifact_storage", item.getStorageType())
                && equalsIgnoreCase("visible", item.getVisibility())
                && equalsIgnoreCase("output", item.getArtifactRole())
                && item.getStorageKey() != null && !item.getStorageKey().isBlank();
    }

    private boolean equals(String expected, String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }
}
