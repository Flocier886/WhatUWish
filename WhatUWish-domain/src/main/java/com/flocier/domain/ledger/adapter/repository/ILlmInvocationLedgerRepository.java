package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;

import java.util.List;
import java.util.Optional;

public interface ILlmInvocationLedgerRepository {

    /** 新增一条 LLM 调用账本记录。 */
    void save(LlmInvocationLedgerEntity entity);

    /** 根据幂等键查询，避免重复写入。 */
    Optional<LlmInvocationLedgerEntity> findByIdempotencyKey(String idempotencyKey);

    /** 查询某次 run 下的所有 LLM 调用记录。 */
    List<LlmInvocationLedgerEntity> findByRunId(String runId);

    /** 批量读取多个 run 下的 LLM 调用，避免调用方逐 run 查询。 */
    List<LlmInvocationLedgerEntity> findByRunIds(List<String> runIds);

    /** 查询某条 trace 下的 LLM 调用记录。 */
    List<LlmInvocationLedgerEntity> findByTraceId(String traceId, Integer limit);
}
