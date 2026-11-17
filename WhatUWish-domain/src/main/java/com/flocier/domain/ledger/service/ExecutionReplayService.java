package com.flocier.domain.ledger.service;

import com.flocier.domain.ledger.model.replay.RunReplayTimeline;
import com.flocier.domain.ledger.service.replay.ReplayProjector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** session/run 历史回放的领域入口，不暴露 Controller 或实时传输协议。 */
@Service
@RequiredArgsConstructor
public class ExecutionReplayService {
    private final ExecutionLedgerReadService executionLedgerReadService;
    private final ReplayProjector replayProjector;

    public RunReplayTimeline replayRun(String runId) {
        return executionLedgerReadService.readRun(runId).getRuns().stream()
                .findFirst().map(replayProjector::project)
                .orElseGet(() -> RunReplayTimeline.builder().runId(runId).entries(List.of()).build());
    }

    public List<RunReplayTimeline> replaySession(String sessionId, Integer limit) {
        return executionLedgerReadService.readSession(sessionId, limit).getRuns().stream()
                .map(replayProjector::project)
                .toList();
    }
}
