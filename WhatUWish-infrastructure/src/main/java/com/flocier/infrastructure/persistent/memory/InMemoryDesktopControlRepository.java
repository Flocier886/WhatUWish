package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRepository;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlStatuses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryDesktopControlRepository implements DesktopControlRepository {
    private final Map<String, DesktopControlRecord> records = new ConcurrentHashMap<>();
    @Override public void insert(DesktopControlRecord record) {
        if (records.putIfAbsent(record.getControlId(), record) != null)
            throw new IllegalStateException("desktop control already exists");
    }
    @Override public Optional<DesktopControlRecord> findByControlId(String id) { return Optional.ofNullable(records.get(id)); }
    @Override public Optional<DesktopControlRecord> findByResumeRequestId(String id) {
        return records.values().stream().filter(row -> id != null && id.equals(row.getResumeRequestId())).findFirst();
    }
    @Override public List<DesktopControlRecord> listBySessionId(String id) {
        List<DesktopControlRecord> rows = new ArrayList<>(records.values().stream()
                .filter(row -> id.equals(row.getSessionId())).toList());
        rows.sort(Comparator.comparing(DesktopControlRecord::getControlId).reversed());
        return rows;
    }
    @Override public synchronized boolean casCompletePending(String id, String requestId, String runId) {
        DesktopControlRecord row = records.get(id);
        if (row == null || !DesktopControlStatuses.PENDING.equals(row.getStatus())
                || row.getHoldUntil() <= System.currentTimeMillis() / 1000d) return false;
        row.setResumeRequestId(requestId); row.setResumeRunId(runId);
        row.setStatus(DesktopControlStatuses.RESUME_PENDING); return true;
    }
    @Override public synchronized boolean casClaimResume(String requestId) {
        DesktopControlRecord row = findByResumeRequestId(requestId).orElse(null);
        if (row == null || !DesktopControlStatuses.RESUME_PENDING.equals(row.getStatus())) return false;
        row.setStatus(DesktopControlStatuses.RESUMING); return true;
    }
    @Override public synchronized boolean markCompleted(String id) { return change(id, DesktopControlStatuses.RESUMING, DesktopControlStatuses.COMPLETED); }
    @Override public synchronized boolean markFailed(String id) {
        return change(id, DesktopControlStatuses.RESUMING, DesktopControlStatuses.FAILED)
                || change(id, DesktopControlStatuses.PENDING, DesktopControlStatuses.FAILED);
    }
    @Override public synchronized boolean casExpire(String id) { return change(id, DesktopControlStatuses.PENDING, DesktopControlStatuses.EXPIRED); }
    private boolean change(String id, String from, String to) {
        DesktopControlRecord row = records.get(id);
        if (row == null || !from.equals(row.getStatus())) return false;
        row.setStatus(to); return true;
    }
}
