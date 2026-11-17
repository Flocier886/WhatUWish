package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;

import java.util.List;
import java.util.Optional;

public interface IArtifactLedgerRepository {

    /** 新增一条产物账本记录。 */
    void save(ArtifactLedgerEntity entity);

    /** 根据幂等键查询，避免重复写入。 */
    Optional<ArtifactLedgerEntity> findByIdempotencyKey(String idempotencyKey);

    /** 按稳定 Artifact 身份查询完整账本事实，供显式迁移使用。 */
    Optional<ArtifactLedgerEntity> findByArtifactId(String artifactId);

    /** 查询受控数量的本地迁移候选；服务层仍会再次验证全部准入条件。 */
    List<ArtifactLedgerEntity> findMigrationCandidates(int limit);

    /** 仅当当前权威 locator 未变化时原子切换存储定位。 */
    boolean updateStorageLocator(String artifactId, String expectedStorageType, String expectedStorageKey,
                                 String newStorageType, String newStorageKey, String newStorageRef);

    /** 查询某次 run 下的所有产物记录。 */
    List<ArtifactLedgerEntity> findByRunId(String runId);

    /** 批量读取多个 run 下的真实可复用产物。 */
    List<ArtifactLedgerEntity> findByRunIds(List<String> runIds);

    /** 批量读取多个 run 的历史产物事实，包含已软删除记录。 */
    List<ArtifactLedgerEntity> findByRunIdsIncludingDeleted(List<String> runIds);

    /** 查询某条 trace 下的产物记录。 */
    List<ArtifactLedgerEntity> findByTraceId(String traceId, Integer limit);

    /** 查询某次工具调用产生的产物记录。 */
    List<ArtifactLedgerEntity> findByToolInvocationId(String toolInvocationId);

    /** 按 run 与 tool call 查询产物记录。 */
    List<ArtifactLedgerEntity> findByRunIdAndToolCallId(String runId, String toolCallId);

    /** 按 request 与 tool call 查询产物记录。 */
    List<ArtifactLedgerEntity> findByRequestIdAndToolCallId(String requestId, String toolCallId);
}
