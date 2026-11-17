package com.flocier.domain.agent.runtime.event;

import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import lombok.Getter;

/** noVNC 地址仅面向当前接管入口；历史回放不发放已过期的流地址。 */
@Getter
public final class DesktopControlPayload implements RuntimeEventPayload {
    private final String presentationSchemaVersion = "v1";
    private final String controlId;
    private final String sourceRunId;
    private final String resumeRunId;
    private final String toolCallId;
    private final String reason;
    private final String streamUrl;
    private final Double holdUntil;
    private final String status;
    private final String resumeRequestId;

    private DesktopControlPayload(DesktopControlRecord record, boolean live) {
        controlId = record.getControlId();
        sourceRunId = record.getSourceRunId();
        resumeRunId = record.getResumeRunId();
        toolCallId = record.getToolCallId();
        reason = record.getReason();
        streamUrl = live && "PENDING".equals(record.getStatus()) ? record.getStreamUrl() : null;
        holdUntil = record.getHoldUntil();
        status = record.getStatus();
        resumeRequestId = record.getResumeRequestId();
    }
    public static DesktopControlPayload from(DesktopControlRecord record) { return new DesktopControlPayload(record, true); }
    public static DesktopControlPayload replay(DesktopControlRecord record) { return new DesktopControlPayload(record, false); }
}
