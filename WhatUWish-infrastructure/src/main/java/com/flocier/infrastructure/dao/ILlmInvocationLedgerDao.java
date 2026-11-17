package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.LlmInvocationLedgerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ILlmInvocationLedgerDao {
    int insert(LlmInvocationLedgerPO po);
    LlmInvocationLedgerPO queryByIdempotencyKey(String idempotencyKey);
    List<LlmInvocationLedgerPO> queryByRunId(String runId);
    List<LlmInvocationLedgerPO> queryByRunIds(@Param("runIds") List<String> runIds);
    List<LlmInvocationLedgerPO> queryByTraceId(@Param("traceId") String traceId, @Param("limit") Integer limit);
}
