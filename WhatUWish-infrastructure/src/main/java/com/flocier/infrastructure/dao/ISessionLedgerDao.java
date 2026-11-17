package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.SessionLedgerPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ISessionLedgerDao {
    int insert(SessionLedgerPO po);
    int updateBySessionId(SessionLedgerPO po);
    SessionLedgerPO queryBySessionId(String sessionId);
    SessionLedgerPO queryByIdempotencyKey(String idempotencyKey);
    List<SessionLedgerPO> queryRecentActiveSessions(Integer limit);
}
