package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.LedgerFactEntity;
import com.flocier.domain.ledger.model.entity.LedgerFactQueryEntity;

import java.util.List;
import java.util.Optional;

public interface ILedgerFactRepository {

    void save(LedgerFactEntity entity);

    Optional<LedgerFactEntity> findByIdempotencyKey(String idempotencyKey);

    List<LedgerFactEntity> query(LedgerFactQueryEntity queryEntity);
}
