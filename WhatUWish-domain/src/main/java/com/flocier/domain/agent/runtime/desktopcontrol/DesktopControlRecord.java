package com.flocier.domain.agent.runtime.desktopcontrol;

import lombok.Data;

/** 桌面接管附属状态；运行与工具事实仍以 Ledger 为准。 */
@Data
public class DesktopControlRecord {
    private Long id;
    private String controlId;
    private String sessionId;
    private String ownerKey;
    private String sourceRunId;
    private String sourceRequestId;
    private String toolInvocationId;
    private String toolCallId;
    private String reason;
    private String streamUrl;
    private Double holdUntil;
    private String status;
    private String resumeRequestId;
    private String resumeRunId;
    private String agentId;
    private Integer maxStep;
}
