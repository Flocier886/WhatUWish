package com.flocier.domain.agent.runtime.event;

import lombok.Getter;

import java.util.Map;

/** 已持久化的 Rich Tool 阶段事实的受控运行时展示数据。 */
@Getter
public final class ToolProgressPayload implements RuntimeEventPayload {
    /** 已提交 runtime_event_ledger 事实的稳定身份。 */
    private final String runtimeEventId;
    private final String toolInvocationId;
    private final String toolName;
    private final Integer toolSequence;
    private final String stage;
    private final String status;
    private final String message;
    private final String artifactId;
    private final String presentationKind;
    private final String presentationSchemaVersion;
    private final Map<String, Object> presentationData;

    public ToolProgressPayload(
            String runtimeEventId,
            String toolInvocationId,
            String toolName,
            Integer toolSequence,
            String stage,
            String status,
            String message,
            String artifactId,
            String presentationKind,
            String presentationSchemaVersion,
            Map<String, Object> presentationData
    ) {
        this.runtimeEventId = runtimeEventId;
        this.toolInvocationId = toolInvocationId;
        this.toolName = toolName;
        this.toolSequence = toolSequence;
        this.stage = stage;
        this.status = status;
        this.message = message;
        this.artifactId = artifactId;
        this.presentationKind = presentationKind;
        this.presentationSchemaVersion = presentationSchemaVersion;
        this.presentationData = presentationData == null ? Map.of() : Map.copyOf(presentationData);
    }
}
