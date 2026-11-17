package com.flocier.application.agent.runtimeevent.history.dto;

import java.time.Instant;

/** Runtime Event v1 的历史 HTTP 展示副本，不暴露领域内部回放时间线。 */
public final class RuntimeHistoryEventDTO {

    private final String schemaVersion;
    private final String eventId;
    private final long sequence;
    private final String eventType;
    private final Instant occurredAt;
    private final String sessionId;
    private final String runId;
    private final String requestId;
    private final String agentId;
    private final Object payload;

    public RuntimeHistoryEventDTO(String schemaVersion, String eventId, long sequence, String eventType,
                                  Instant occurredAt, String sessionId, String runId, String requestId,
                                  String agentId, Object payload) {
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

    public String getSchemaVersion() { return schemaVersion; }
    public String getEventId() { return eventId; }
    public long getSequence() { return sequence; }
    public String getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getSessionId() { return sessionId; }
    public String getRunId() { return runId; }
    public String getRequestId() { return requestId; }
    public String getAgentId() { return agentId; }
    public Object getPayload() { return payload; }
}
