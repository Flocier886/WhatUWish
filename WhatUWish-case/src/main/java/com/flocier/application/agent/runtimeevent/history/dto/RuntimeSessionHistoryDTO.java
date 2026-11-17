package com.flocier.application.agent.runtimeevent.history.dto;

import java.util.List;

public final class RuntimeSessionHistoryDTO {

    private final RuntimeSessionSummaryDTO session;
    private final List<RuntimeRunReplayDTO> replays;
    private final boolean historical;

    public RuntimeSessionHistoryDTO(RuntimeSessionSummaryDTO session, List<RuntimeRunReplayDTO> replays,
                                    boolean historical) {
        this.session = session;
        this.replays = replays;
        this.historical = historical;
    }

    public RuntimeSessionSummaryDTO getSession() { return session; }
    public List<RuntimeRunReplayDTO> getReplays() { return replays; }
    public boolean isHistorical() { return historical; }
}
