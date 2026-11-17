package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.ILedgerFactRepository;
import com.flocier.domain.ledger.model.entity.LedgerFactEntity;
import com.flocier.domain.ledger.model.entity.LedgerFactQueryEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryLedgerFactRepository implements ILedgerFactRepository {

    private final CopyOnWriteArrayList<LedgerFactEntity> storage = new CopyOnWriteArrayList<>();

    @Override
    public void save(LedgerFactEntity entity) {
        storage.add(entity);
    }

    @Override
    public Optional<LedgerFactEntity> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return storage.stream()
                .filter(item -> idempotencyKey.equals(item.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public List<LedgerFactEntity> query(LedgerFactQueryEntity queryEntity) {
        int limit = queryEntity == null || queryEntity.getLimit() == null || queryEntity.getLimit() <= 0
                ? 100
                : queryEntity.getLimit();
        Instant occurredAfter = queryEntity == null ? null : queryEntity.getOccurredAfter();
        Instant occurredBefore = queryEntity == null ? null : queryEntity.getOccurredBefore();

        return storage.stream()
                .filter(item -> queryEntity == null || queryEntity.getSessionId() == null || queryEntity.getSessionId().equals(item.getSessionId()))
                .filter(item -> queryEntity == null || queryEntity.getTraceId() == null || queryEntity.getTraceId().equals(item.getTraceId()))
                .filter(item -> queryEntity == null || queryEntity.getRequestId() == null || queryEntity.getRequestId().equals(item.getRequestId()))
                .filter(item -> queryEntity == null || queryEntity.getSourceType() == null || queryEntity.getSourceType().equals(item.getSourceType()))
                .filter(item -> queryEntity == null || queryEntity.getFactType() == null || queryEntity.getFactType() == item.getFactType())
                .filter(item -> queryEntity == null || queryEntity.getStepNo() == null || queryEntity.getStepNo().equals(item.getStepNo()))
                .filter(item -> occurredAfter == null || (item.getOccurredAt() != null && !item.getOccurredAt().isBefore(occurredAfter)))
                .filter(item -> occurredBefore == null || (item.getOccurredAt() != null && !item.getOccurredAt().isAfter(occurredBefore)))
                .sorted(Comparator.comparing(LedgerFactEntity::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(limit)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
