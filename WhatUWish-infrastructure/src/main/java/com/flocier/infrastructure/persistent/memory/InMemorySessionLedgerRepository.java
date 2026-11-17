package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.ISessionLedgerRepository;
import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemorySessionLedgerRepository implements ISessionLedgerRepository {

    private final CopyOnWriteArrayList<SessionLedgerEntity> storage = new CopyOnWriteArrayList<>();

    @Override
    public void save(SessionLedgerEntity entity) {
        storage.add(entity);
    }

    @Override
    public void update(SessionLedgerEntity entity) {
        if (entity == null || entity.getSessionId() == null) {
            return;
        }
        for (int i = 0; i < storage.size(); i++) {
            if (entity.getSessionId().equals(storage.get(i).getSessionId())) {
                storage.set(i, entity);
                return;
            }
        }
        storage.add(entity);
    }

    @Override
    public Optional<SessionLedgerEntity> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return storage.stream().filter(item -> sessionId.equals(item.getSessionId())).findFirst();
    }

    @Override
    public Optional<SessionLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return storage.stream().filter(item -> idempotencyKey.equals(item.getIdempotencyKey())).findFirst();
    }

    @Override
    public List<SessionLedgerEntity> findRecentActiveSessions(Integer limit) {
        return storage.stream()
                .sorted(Comparator.comparing(SessionLedgerEntity::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit == null || limit <= 0 ? 20 : limit)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
