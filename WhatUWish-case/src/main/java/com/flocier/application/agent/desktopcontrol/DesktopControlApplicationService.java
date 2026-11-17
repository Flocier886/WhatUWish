package com.flocier.application.agent.desktopcontrol;

import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRepository;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlStatuses;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopSessionPort;
import com.flocier.domain.agent.runtime.event.DesktopControlPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 单用户会话内的接管状态迁移；完成动作不直接执行 Agent。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DesktopControlApplicationService {
    private final DesktopControlRepository repository;
    private final DesktopSessionPort desktopSessionPort;

    public List<DesktopControlPayload> list(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为空");
        return repository.listBySessionId(sessionId).stream().map(record -> {
            expireIfNeeded(record);
            return DesktopControlPayload.from(record);
        }).toList();
    }

    public DesktopControlRecord requireResume(String resumeRequestId) {
        return repository.findByResumeRequestId(resumeRequestId)
                .orElseThrow(() -> new IllegalArgumentException("续跑记录不存在"));
    }

    public DesktopControlRecord complete(String sessionId, String controlId) {
        DesktopControlRecord record = requireOwned(sessionId, controlId);
        expireIfNeeded(record);
        if (DesktopControlStatuses.PENDING.equals(record.getStatus())) {
            String resumeRequestId = "resume_" + UUID.randomUUID().toString().replace("-", "");
            String resumeRunId = UUID.randomUUID().toString();
            if (repository.casCompletePending(controlId, resumeRequestId, resumeRunId)) {
                closeQuietly(record);
                return requireOwned(sessionId, controlId);
            }
            record = requireOwned(sessionId, controlId);
        }
        if (record.getResumeRequestId() != null) return record;
        throw new IllegalStateException("桌面接管已过期或结束");
    }

    public DesktopControlRecord claimResume(String sessionId, String resumeRequestId) {
        DesktopControlRecord record = requireResume(resumeRequestId);
        if (!record.getSessionId().equals(sessionId)) throw new IllegalArgumentException("会话不匹配");
        if (!repository.casClaimResume(resumeRequestId)) throw new IllegalStateException("续跑已被认领或结束");
        return requireResume(resumeRequestId);
    }

    public void finish(String controlId, boolean success) {
        if (success) repository.markCompleted(controlId);
        else repository.markFailed(controlId);
    }

    private DesktopControlRecord requireOwned(String sessionId, String controlId) {
        DesktopControlRecord record = repository.findByControlId(controlId)
                .orElseThrow(() -> new IllegalArgumentException("桌面接管不存在"));
        if (sessionId == null || !sessionId.equals(record.getSessionId()))
            throw new IllegalArgumentException("会话不匹配");
        return record;
    }

    private void expireIfNeeded(DesktopControlRecord record) {
        if (DesktopControlStatuses.PENDING.equals(record.getStatus()) && record.getHoldUntil() != null
                && record.getHoldUntil() <= System.currentTimeMillis() / 1000d
                && repository.casExpire(record.getControlId())) {
            record.setStatus(DesktopControlStatuses.EXPIRED);
            closeQuietly(record);
        }
    }

    private void closeQuietly(DesktopControlRecord record) {
        try { desktopSessionPort.close(record.getSourceRequestId(), record.getSessionId(), record.getOwnerKey()); }
        catch (RuntimeException failure) { log.warn("关闭桌面 hold 失败, controlId={}", record.getControlId(), failure); }
    }
}
