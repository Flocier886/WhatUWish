package com.flocier.infrastructure.adapter.repository;

import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRepository;
import com.flocier.infrastructure.dao.IDesktopControlDao;
import com.flocier.infrastructure.dao.po.DesktopControlPO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "mysql")
public class MysqlDesktopControlRepository implements DesktopControlRepository {
    private final IDesktopControlDao dao;

    @Override public void insert(DesktopControlRecord record) { dao.insert(copy(record)); }
    @Override public Optional<DesktopControlRecord> findByControlId(String id) {
        return Optional.ofNullable(dao.selectByControlId(id));
    }
    @Override public Optional<DesktopControlRecord> findByResumeRequestId(String id) {
        return Optional.ofNullable(dao.selectByResumeRequestId(id));
    }
    @Override public List<DesktopControlRecord> listBySessionId(String id) {
        return List.copyOf(dao.selectBySessionId(id));
    }
    @Override public boolean casCompletePending(String id, String requestId, String runId) {
        return dao.casCompletePending(id, requestId, runId) == 1;
    }
    @Override public boolean casClaimResume(String id) { return dao.casClaimResume(id) == 1; }
    @Override public boolean markCompleted(String id) { return dao.markCompleted(id) == 1; }
    @Override public boolean markFailed(String id) { return dao.markFailed(id) == 1; }
    @Override public boolean casExpire(String id) { return dao.casExpire(id) == 1; }

    private DesktopControlPO copy(DesktopControlRecord source) {
        DesktopControlPO row = new DesktopControlPO();
        row.setControlId(source.getControlId());
        row.setSessionId(source.getSessionId());
        row.setOwnerKey(source.getOwnerKey());
        row.setSourceRunId(source.getSourceRunId());
        row.setSourceRequestId(source.getSourceRequestId());
        row.setToolInvocationId(source.getToolInvocationId());
        row.setToolCallId(source.getToolCallId());
        row.setReason(source.getReason());
        row.setStreamUrl(source.getStreamUrl());
        row.setHoldUntil(source.getHoldUntil());
        row.setStatus(source.getStatus());
        row.setResumeRequestId(source.getResumeRequestId());
        row.setResumeRunId(source.getResumeRunId());
        row.setAgentId(source.getAgentId());
        row.setMaxStep(source.getMaxStep());
        return row;
    }
}
