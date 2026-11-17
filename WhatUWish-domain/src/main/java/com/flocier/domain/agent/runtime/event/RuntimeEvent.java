package com.flocier.domain.agent.runtime.event;

import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/** 已分配实时顺序的 Runtime Event v1。 */
@Getter
public final class RuntimeEvent {

    public static final String SCHEMA_VERSION_V1 = "v1";

    private final String schemaVersion;
    private final String eventId;
    private final long sequence;
    private final RuntimeEventType eventType;
    private final Instant occurredAt;
    private final String sessionId;
    private final String runId;
    private final String requestId;
    private final String agentId;
    private final RuntimeEventPayload payload;

    public RuntimeEvent(String schemaVersion, String eventId, long sequence, RuntimeEventType eventType,
                        Instant occurredAt, String sessionId, String runId, String requestId,
                        String agentId, RuntimeEventPayload payload) {
        if (!SCHEMA_VERSION_V1.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported runtime event schema version: " + schemaVersion);
        }
        if (eventId == null || eventId.isBlank() || sequence < 1) {
            throw new IllegalArgumentException("Runtime event requires eventId and positive sequence");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        this.schemaVersion = schemaVersion;
        this.eventId = eventId;
        this.sequence = sequence;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.sessionId = sessionId;
        this.runId = runId;
        this.requestId = requestId;
        this.agentId = agentId;
        this.payload = payload;
    }

    public boolean isTerminal() {
        return eventType == RuntimeEventType.RUN_COMPLETED || eventType == RuntimeEventType.RUN_FAILED
                || eventType == RuntimeEventType.RUN_WAITING_USER_INPUT;
    }
}
