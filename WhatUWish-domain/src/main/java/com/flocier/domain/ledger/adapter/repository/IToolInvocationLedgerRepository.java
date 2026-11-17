package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;

import java.util.List;
import java.util.Optional;

public interface IToolInvocationLedgerRepository {

    /** 新增一条 Tool 调用账本记录。 */
    void save(ToolInvocationLedgerEntity entity);

    /** 更新同一条 Tool Invocation 的终态事实，供长任务工具完成生命周期。 */
    void update(ToolInvocationLedgerEntity entity);

    /** 根据幂等键查询，避免重复写入。 */
    Optional<ToolInvocationLedgerEntity> findByIdempotencyKey(String idempotencyKey);

    /** 查询某次 run 下的所有 Tool 调用记录。 */
    List<ToolInvocationLedgerEntity> findByRunId(String runId);

    /** 批量读取多个 run 下的工具调用，避免调用方逐 run 查询。 */
    List<ToolInvocationLedgerEntity> findByRunIds(List<String> runIds);

    /** 查询某条 trace 下的 Tool 调用记录。 */
    List<ToolInvocationLedgerEntity> findByTraceId(String traceId, Integer limit);
}
