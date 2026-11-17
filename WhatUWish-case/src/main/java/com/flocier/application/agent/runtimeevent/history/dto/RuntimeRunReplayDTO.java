package com.flocier.application.agent.runtimeevent.history.dto;

import java.time.Instant;
import java.util.List;

public final class RuntimeRunReplayDTO {

    private final String runId;
    private final String sessionId;
    private final String requestId;
    private final String agentId;
    private final String task;
    private final String status;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final String summary;
    private final String errorCode;
    private final String errorMessage;
    private final boolean historical;
    private final boolean dataIncomplete;
    private final List<RuntimeHistoryEventDTO> events;

    public RuntimeRunReplayDTO(String runId, String sessionId, String requestId, String agentId, String task,
                               String status, Instant startedAt, Instant finishedAt, String summary,
                               String errorCode, String errorMessage, boolean historical, boolean dataIncomplete,
                               List<RuntimeHistoryEventDTO> events) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.agentId = agentId;
        this.task = task;
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.summary = summary;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.historical = historical;
        this.dataIncomplete = dataIncomplete;
        this.events = events;
    }

    public String getRunId() { return runId; }
    public String getSessionId() { return sessionId; }
    public String getRequestId() { return requestId; }
    public String getAgentId() { return agentId; }
    public String getTask() { return task; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getSummary() { return summary; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isHistorical() { return historical; }
    public boolean isDataIncomplete() { return dataIncomplete; }
    public List<RuntimeHistoryEventDTO> getEvents() { return events; }
}
