package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.ArtifactLedgerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IArtifactLedgerDao {
    int insert(ArtifactLedgerPO po);
    ArtifactLedgerPO queryByIdempotencyKey(String idempotencyKey);
    ArtifactLedgerPO queryByArtifactId(String artifactId);
    List<ArtifactLedgerPO> queryMigrationCandidates(@Param("limit") int limit);
    int updateStorageLocator(@Param("artifactId") String artifactId,
                             @Param("expectedStorageType") String expectedStorageType,
                             @Param("expectedStorageKey") String expectedStorageKey,
                             @Param("newStorageType") String newStorageType,
                             @Param("newStorageKey") String newStorageKey,
                             @Param("newStorageRef") String newStorageRef);
    List<ArtifactLedgerPO> queryByRunId(String runId);
    List<ArtifactLedgerPO> queryByRunIds(@Param("runIds") List<String> runIds);
    List<ArtifactLedgerPO> queryByRunIdsIncludingDeleted(@Param("runIds") List<String> runIds);
    List<ArtifactLedgerPO> queryByTraceId(@Param("traceId") String traceId, @Param("limit") Integer limit);
    List<ArtifactLedgerPO> queryByToolInvocationId(String toolInvocationId);
    List<ArtifactLedgerPO> queryByRunIdAndToolCallId(@Param("runId") String runId, @Param("toolCallId") String toolCallId);
    List<ArtifactLedgerPO> queryByRequestIdAndToolCallId(@Param("requestId") String requestId, @Param("toolCallId") String toolCallId);
}
