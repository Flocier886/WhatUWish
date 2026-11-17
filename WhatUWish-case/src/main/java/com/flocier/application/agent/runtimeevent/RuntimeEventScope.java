package com.flocier.application.agent.runtimeevent;

/** 一次 Run 的固定展示身份。 */
public final class RuntimeEventScope {

    private final String sessionId;
    private final String runId;
    private final String requestId;
    private final String agentId;

    public RuntimeEventScope(String sessionId, String runId, String requestId, String agentId) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.requestId = requestId;
        this.agentId = agentId;
    }

    public String getSessionId() { return sessionId; }
    public String getRunId() { return runId; }
    public String getRequestId() { return requestId; }
    public String getAgentId() { return agentId; }
}
