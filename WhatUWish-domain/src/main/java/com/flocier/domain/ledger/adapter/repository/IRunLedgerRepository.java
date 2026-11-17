package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerStats;

import java.util.List;
import java.util.Optional;

public interface IRunLedgerRepository {

    /** 新增一条 run 账本记录。 */
    void save(RunLedgerEntity entity);

    /** 按 runId 更新已有 run 账本记录。 */
    void update(RunLedgerEntity entity);

    /** 根据 runId 查询执行主记录。 */
    Optional<RunLedgerEntity> findByRunId(String runId);

    /** 根据 requestId 查询执行主记录。 */
    Optional<RunLedgerEntity> findByRequestId(String requestId);

    /** 根据幂等键查询，避免重复创建 run。 */
    Optional<RunLedgerEntity> findByIdempotencyKey(String idempotencyKey);

    /** 查询某个会话下的执行列表。 */
    List<RunLedgerEntity> findBySessionId(String sessionId, Integer limit);

    /**
     * 按 sessionId 从 run 账本事实重算统计快照。
     * 默认实现复用已有查询能力；持久化实现后续可覆盖为数据库聚合。
     */
    default RunLedgerStats countBySessionId(String sessionId) {
        return RunLedgerStats.fromRuns(findBySessionId(sessionId, Integer.MAX_VALUE));
    }

    /** 查询某条 trace 下的执行列表。 */
    List<RunLedgerEntity> findByTraceId(String traceId, Integer limit);
}
