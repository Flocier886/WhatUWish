package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.DeepSearchExecutionEntity;

import java.util.Optional;

public interface IDeepSearchExecutionRepository {
    void save(DeepSearchExecutionEntity entity);
    void update(DeepSearchExecutionEntity entity);
    Optional<DeepSearchExecutionEntity> findByToolInvocationId(String toolInvocationId);
}
