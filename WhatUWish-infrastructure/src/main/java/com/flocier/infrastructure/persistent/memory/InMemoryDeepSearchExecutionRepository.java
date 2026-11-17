package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.ledger.adapter.repository.IDeepSearchExecutionRepository;
import com.flocier.domain.ledger.model.entity.DeepSearchExecutionEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryDeepSearchExecutionRepository implements IDeepSearchExecutionRepository {
    private final ConcurrentHashMap<String, DeepSearchExecutionEntity> storage = new ConcurrentHashMap<>();
    @Override public void save(DeepSearchExecutionEntity entity) {
        if (storage.putIfAbsent(entity.getToolInvocationId(), entity) != null) throw new IllegalStateException("DeepSearch execution already exists");
    }
    @Override public void update(DeepSearchExecutionEntity entity) { storage.put(entity.getToolInvocationId(), entity); }
    @Override public Optional<DeepSearchExecutionEntity> findByToolInvocationId(String toolInvocationId) { return Optional.ofNullable(storage.get(toolInvocationId)); }
}
