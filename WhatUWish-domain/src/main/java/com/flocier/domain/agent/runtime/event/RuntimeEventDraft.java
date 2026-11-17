package com.flocier.domain.agent.runtime.event;

import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/**
 * 领域事实投影出的事件草稿。sequence 和 eventId 属于实时发布会话，由应用层补齐。
 */
@Getter
public final class RuntimeEventDraft {

    private final String eventId;
    private final RuntimeEventType eventType;
    private final Instant occurredAt;
    private final String sessionId;
    private final String runId;
    private final String requestId;
    private final String agentId;
    private final RuntimeEventPayload payload;

    public RuntimeEventDraft(RuntimeEventType eventType, Instant occurredAt, String sessionId, String runId,
                             String requestId, String agentId, RuntimeEventPayload payload) {
        this(null, eventType, occurredAt, sessionId, runId, requestId, agentId, payload);
    }

    private RuntimeEventDraft(
            String eventId,
            RuntimeEventType eventType,
            Instant occurredAt,
            String sessionId,
            String runId,
            String requestId,
            String agentId,
            RuntimeEventPayload payload
    ) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        this.eventId = eventId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.sessionId = sessionId;
        this.runId = runId;
        this.requestId = requestId;
        this.agentId = agentId;
        this.payload = payload;
    }

    /**
     * 只允许已提交的 Rich Tool 过程事实指定 Runtime Event 身份。
     * 普通运行事件仍由实时会话分配临时事件 ID。
     */
    public static RuntimeEventDraft persistedToolProgress(
            String runtimeEventId,
            Instant occurredAt,
            String sessionId,
            String runId,
            String requestId,
            String agentId,
            ToolProgressPayload payload
    ) {
        if (!hasText(runtimeEventId)
                || payload == null
                || !runtimeEventId.equals(payload.getRuntimeEventId())) {
            throw new IllegalArgumentException("Persisted tool progress requires matching runtimeEventId");
        }
        return new RuntimeEventDraft(
                runtimeEventId,
                RuntimeEventType.TOOL_PROGRESS,
                occurredAt,
                sessionId,
                runId,
                requestId,
                agentId,
                payload
        );
    }

    public boolean hasRequiredIdentity() {
        return hasText(sessionId) && hasText(runId) && hasText(requestId) && hasText(agentId);
    }

    /** 已落账事实的稳定去重键；RUN_STATUS 是瞬态信息，不参与事实去重。 */
    public String factKey() {
        if (payload instanceof LlmResponsePayload value) return eventType + ":" + value.getLlmInvocationId();
        if (payload instanceof ToolStartedPayload value) return eventType + ":" + value.getToolInvocationId();
        if (payload instanceof ToolResultPayload value) return eventType + ":" + value.getToolInvocationId();
        if (payload instanceof ArtifactCreatedPayload value) return eventType + ":" + value.getArtifactId();
        if (payload instanceof InputFilePayload value) return eventType + ":" + value.getFileId();
        if (payload instanceof DesktopControlPayload value) return eventType + ":" + value.getControlId();
        if (payload instanceof ToolProgressPayload value) {
            return hasText(value.getRuntimeEventId()) ? eventType + ":" + value.getRuntimeEventId() : null;
        }
        if (payload instanceof RunStartedPayload || payload instanceof RunCompletedPayload
                || payload instanceof RunFailedPayload || payload instanceof RunWaitingUserInputPayload) {
            return eventType + ":run";
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
