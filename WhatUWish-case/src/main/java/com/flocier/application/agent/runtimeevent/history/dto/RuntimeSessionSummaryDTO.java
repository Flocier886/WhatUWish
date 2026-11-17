package com.flocier.application.agent.runtimeevent.history.dto;

import java.time.Instant;

public final class RuntimeSessionSummaryDTO {

    private final String sessionId;
    private final String title;
    private final String agentId;
    private final String status;
    private final String latestQueryText;
    private final String latestSummaryText;
    private final int runCount;
    private final int finishedRunCount;
    private final int failedRunCount;
    private final Instant startedAt;
    private final Instant updatedAt;
    private final boolean historical;

    public RuntimeSessionSummaryDTO(String sessionId, String title, String agentId, String status,
                                    String latestQueryText, String latestSummaryText, int runCount,
                                    int finishedRunCount, int failedRunCount, Instant startedAt,
                                    Instant updatedAt, boolean historical) {
        this.sessionId = sessionId;
        this.title = title;
        this.agentId = agentId;
        this.status = status;
        this.latestQueryText = latestQueryText;
        this.latestSummaryText = latestSummaryText;
        this.runCount = runCount;
        this.finishedRunCount = finishedRunCount;
        this.failedRunCount = failedRunCount;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
        this.historical = historical;
    }

    public String getSessionId() { return sessionId; }
    public String getTitle() { return title; }
    public String getAgentId() { return agentId; }
    public String getStatus() { return status; }
    public String getLatestQueryText() { return latestQueryText; }
    public String getLatestSummaryText() { return latestSummaryText; }
    public int getRunCount() { return runCount; }
    public int getFinishedRunCount() { return finishedRunCount; }
    public int getFailedRunCount() { return failedRunCount; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isHistorical() { return historical; }
}
