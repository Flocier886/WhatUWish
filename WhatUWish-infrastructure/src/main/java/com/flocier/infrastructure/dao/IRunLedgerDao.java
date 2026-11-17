package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.RunLedgerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IRunLedgerDao {
    int insert(RunLedgerPO po);
    int updateByRunId(RunLedgerPO po);
    RunLedgerPO queryByRunId(String runId);
    RunLedgerPO queryByRequestId(String requestId);
    RunLedgerPO queryByIdempotencyKey(String idempotencyKey);
    List<RunLedgerPO> queryBySessionId(@Param("sessionId") String sessionId, @Param("limit") Integer limit);
    List<RunLedgerPO> queryByTraceId(@Param("traceId") String traceId, @Param("limit") Integer limit);
}
