package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.ILlmInvocationLedgerRepository;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryLlmInvocationLedgerRepository implements ILlmInvocationLedgerRepository {

    private final CopyOnWriteArrayList<LlmInvocationLedgerEntity> storage = new CopyOnWriteArrayList<>();

    @Override
    public void save(LlmInvocationLedgerEntity entity) {
        storage.add(entity);
    }

    @Override
    public Optional<LlmInvocationLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return storage.stream().filter(item -> idempotencyKey.equals(item.getIdempotencyKey())).findFirst();
    }

    @Override
    public List<LlmInvocationLedgerEntity> findByRunId(String runId) {
        return storage.stream()
                .filter(item -> runId == null || runId.equals(item.getRunId()))
                .sorted(Comparator.comparing(LlmInvocationLedgerEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public List<LlmInvocationLedgerEntity> findByRunIds(List<String> runIds) {
        return runIds == null || runIds.isEmpty() ? List.of() : storage.stream()
                .filter(item -> runIds.contains(item.getRunId()))
                .sorted(Comparator.comparing(LlmInvocationLedgerEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public List<LlmInvocationLedgerEntity> findByTraceId(String traceId, Integer limit) {
        return storage.stream()
                .filter(item -> traceId == null || traceId.equals(item.getTraceId()))
                .sorted(Comparator.comparing(LlmInvocationLedgerEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(limit == null || limit <= 0 ? 100 : limit)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
