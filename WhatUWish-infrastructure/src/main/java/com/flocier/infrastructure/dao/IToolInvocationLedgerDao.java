package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.ToolInvocationLedgerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IToolInvocationLedgerDao {
    int insert(ToolInvocationLedgerPO po);
    int update(ToolInvocationLedgerPO po);
    ToolInvocationLedgerPO queryByIdempotencyKey(String idempotencyKey);
    List<ToolInvocationLedgerPO> queryByRunId(String runId);
    List<ToolInvocationLedgerPO> queryByRunIds(@Param("runIds") List<String> runIds);
    List<ToolInvocationLedgerPO> queryByTraceId(@Param("traceId") String traceId, @Param("limit") Integer limit);
}
