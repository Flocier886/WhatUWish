package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;

import java.util.List;
import java.util.Optional;

/**
 * Session 账本仓储接口。
 * Session 账本描述的是一个可持续会话容器，不等同于单次 run。
 */
public interface ISessionLedgerRepository {

    /**
     * 新增一条 session 账本记录。
     */
    void save(SessionLedgerEntity entity);

    /**
     * 按 sessionId 更新已有 session 账本记录。
     */
    void update(SessionLedgerEntity entity);

    /**
     * 根据 sessionId 查询会话账本头信息。
     */
    Optional<SessionLedgerEntity> findBySessionId(String sessionId);

    /**
     * 根据幂等键查询，避免重复创建 session。
     */
    Optional<SessionLedgerEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * 查询最近活跃的 session 列表。
     */
    List<SessionLedgerEntity> findRecentActiveSessions(Integer limit);
}
