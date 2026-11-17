package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.event.RunCompletedPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.LedgerQueryService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CodeExecutionRunCompletedEventTest {
    @Test
    public void liveTerminalUsesCommittedVisibleArtifacts() {
        LedgerRecordingService recording = mock(LedgerRecordingService.class);
        LedgerQueryService query = mock(LedgerQueryService.class);
        RunLedgerEntity run = RunLedgerEntity.builder().runId("run-1").sessionId("session-1").agentId("agent-1")
                .requestId("request-1").status("COMPLETED").summary("done")
                .finishedAt(Instant.now()).build();
        AgentContext context = AgentContext.builder().sessionId("session-1").requestId("request-1").build();
        when(recording.recordRunFinished(eq(context), eq("COMPLETED"), eq("done"), any(), any()))
                .thenReturn(run);
        ArtifactLedgerEntity visible = ArtifactLedgerEntity.builder().artifactId("visible-1")
                .visibility("visible").deleted(0).build();
        ArtifactLedgerEntity source = ArtifactLedgerEntity.builder().artifactId("source-1")
                .visibility("hidden").deleted(0).build();
        ArtifactLedgerEntity removed = ArtifactLedgerEntity.builder().artifactId("removed-1")
                .visibility("visible").deleted(1).build();
        when(query.queryRunView("run-1")).thenReturn(Optional.of(LedgerRunView.builder()
                .artifacts(List.of(visible, source, removed)).build()));
        List<RuntimeEventDraft> events = new ArrayList<>();
        LedgerFactService service = new LedgerFactService(recording, query, new RuntimeEventMapper(), events::add);

        service.recordRunFinished(context, "COMPLETED", "done", null, null);

        assertEquals(1, events.size());
        RunCompletedPayload payload = (RunCompletedPayload) events.get(0).getPayload();
        assertEquals(List.of("visible-1"), payload.getArtifactIds());
    }
}
