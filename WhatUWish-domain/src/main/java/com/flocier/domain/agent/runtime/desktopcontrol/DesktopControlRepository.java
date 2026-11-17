package com.flocier.domain.agent.runtime.desktopcontrol;

import java.util.List;
import java.util.Optional;

public interface DesktopControlRepository {
    void insert(DesktopControlRecord record);
    Optional<DesktopControlRecord> findByControlId(String controlId);
    Optional<DesktopControlRecord> findByResumeRequestId(String resumeRequestId);
    List<DesktopControlRecord> listBySessionId(String sessionId);
    boolean casCompletePending(String controlId, String resumeRequestId, String resumeRunId);
    boolean casClaimResume(String resumeRequestId);
    boolean markCompleted(String controlId);
    boolean markFailed(String controlId);
    boolean casExpire(String controlId);
}
