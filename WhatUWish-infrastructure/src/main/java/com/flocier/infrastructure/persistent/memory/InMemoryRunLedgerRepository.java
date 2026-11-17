package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.IRunLedgerRepository;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerStats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryRunLedgerRepository implements IRunLedgerRepository {

    private final CopyOnWriteArrayList<RunLedgerEntity> storage = new CopyOnWriteArrayList<>();

    @Override
    public void save(RunLedgerEntity entity) {
        storage.add(entity);
    }

    @Override
    public void update(RunLedgerEntity entity) {
        if (entity == null || entity.getRunId() == null) {
            return;
        }
        for (int i = 0; i < storage.size(); i++) {
            if (entity.getRunId().equals(storage.get(i).getRunId())) {
                storage.set(i, entity);
                return;
            }
        }
        storage.add(entity);
    }

    @Override
    public Optional<RunLedgerEntity> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return storage.stream().filter(item -> runId.equals(item.getRunId())).findFirst();
    }

    @Override
    public Optional<RunLedgerEntity> findByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return storage.stream().filter(item -> requestId.equals(item.getRequestId())).findFirst();
    }

    @Override
    public Optional<RunLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return storage.stream().filter(item -> idempotencyKey.equals(item.getIdempotencyKey())).findFirst();
    }

    @Override
    public List<RunLedgerEntity> findBySessionId(String sessionId, Integer limit) {
        return storage.stream()
                .filter(item -> sessionId == null || sessionId.equals(item.getSessionId()))
                .sorted(Comparator.comparing(RunLedgerEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(normalizeLimit(limit))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public RunLedgerStats countBySessionId(String sessionId) {
        return RunLedgerStats.fromRuns(findBySessionId(sessionId, Integer.MAX_VALUE));
    }

    @Override
    public List<RunLedgerEntity> findByTraceId(String traceId, Integer limit) {
        return storage.stream()
                .filter(item -> traceId == null || traceId.equals(item.getTraceId()))
                .sorted(Comparator.comparing(RunLedgerEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(normalizeLimit(limit))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private int normalizeLimit(Integer limit) {
        return limit == null || limit <= 0 ? 100 : limit;
    }
}
