package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.RuntimeEventLedgerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IRuntimeEventLedgerDao {
    int insert(RuntimeEventLedgerPO po);
    RuntimeEventLedgerPO queryByInvocationAndUpstreamEvent(@Param("toolInvocationId") String toolInvocationId, @Param("upstreamEventId") String upstreamEventId);
    RuntimeEventLedgerPO queryByInvocationAndSequence(@Param("toolInvocationId") String toolInvocationId, @Param("sequenceNo") Integer sequenceNo);
    List<RuntimeEventLedgerPO> queryByToolInvocationId(String toolInvocationId);
    List<RuntimeEventLedgerPO> queryByRunId(String runId);
}
