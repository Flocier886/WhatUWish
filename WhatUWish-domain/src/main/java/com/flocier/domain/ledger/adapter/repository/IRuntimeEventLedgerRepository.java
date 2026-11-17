package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;

import java.util.List;
import java.util.Optional;

public interface IRuntimeEventLedgerRepository {
    void save(RuntimeEventLedgerEntity entity);
    Optional<RuntimeEventLedgerEntity> findByToolInvocationIdAndUpstreamEventId(String toolInvocationId, String upstreamEventId);
    Optional<RuntimeEventLedgerEntity> findByToolInvocationIdAndSequenceNo(String toolInvocationId, Integer sequenceNo);
    List<RuntimeEventLedgerEntity> findByToolInvocationIdOrderBySequenceNo(String toolInvocationId);
    List<RuntimeEventLedgerEntity> findByRunIdOrderByInvocationAndSequenceNo(String runId);
}
