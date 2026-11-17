package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.IRuntimeEventLedgerRepository;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryRuntimeEventLedgerRepository implements IRuntimeEventLedgerRepository {
    private final CopyOnWriteArrayList<RuntimeEventLedgerEntity> storage = new CopyOnWriteArrayList<>();

    @Override
    public void save(RuntimeEventLedgerEntity entity) {
        storage.add(entity);
    }

    @Override
    public Optional<RuntimeEventLedgerEntity> findByToolInvocationIdAndUpstreamEventId(String invocationId, String eventId) {
        return storage.stream().filter(item -> invocationId.equals(item.getToolInvocationId()) && eventId.equals(item.getUpstreamEventId())).findFirst();
    }
    @Override
    public Optional<RuntimeEventLedgerEntity> findByToolInvocationIdAndSequenceNo(String invocationId, Integer sequenceNo) {
        return storage.stream().filter(item -> invocationId.equals(item.getToolInvocationId()) && sequenceNo.equals(item.getSequenceNo())).findFirst();
    }
    @Override
    public List<RuntimeEventLedgerEntity> findByToolInvocationIdOrderBySequenceNo(String invocationId) {
        return storage.stream().filter(item -> invocationId.equals(item.getToolInvocationId()))
                .sorted(Comparator.comparing(RuntimeEventLedgerEntity::getSequenceNo)).toList();
    }

    @Override
    public List<RuntimeEventLedgerEntity> findByRunIdOrderByInvocationAndSequenceNo(String runId) {
        return storage.stream()
                .filter(item -> runId.equals(item.getRunId()))
                .sorted(Comparator.comparing(RuntimeEventLedgerEntity::getToolInvocationId)
                        .thenComparing(RuntimeEventLedgerEntity::getSequenceNo)
                        .thenComparing(RuntimeEventLedgerEntity::getRuntimeEventId))
                .toList();
    }
}
