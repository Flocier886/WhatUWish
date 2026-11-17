package com.flocier.domain.ledger.service.replay.rich;

import com.flocier.domain.agent.runtime.event.RuntimeEvent;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.event.RunStatusPayload;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class RichRuntimeReplayProjectorRegistryTest {

    @Test
    public void shouldMergeRegisteredProjectionsAndKeepIncompleteSignal() {
        RichRuntimeReplayProjector first = run -> new RichRuntimeReplayProjection(List.of(event("first")), false);
        RichRuntimeReplayProjector second = run -> new RichRuntimeReplayProjection(List.of(event("second")), true);

        RichRuntimeReplayProjection projection = new RichRuntimeReplayProjectorRegistry(List.of(first, second))
                .project(RunLedgerEntity.builder().runId("run-1").build());

        Assert.assertEquals(List.of("first", "second"), projection.getEvents().stream().map(RuntimeEvent::getEventId).toList());
        Assert.assertTrue(projection.isDataIncomplete());
        Assert.assertTrue(new RichRuntimeReplayProjectorRegistry(List.of()).project(null).getEvents().isEmpty());
    }

    private RuntimeEvent event(String id) {
        return new RuntimeEvent(RuntimeEvent.SCHEMA_VERSION_V1, id, 1, RuntimeEventType.TOOL_PROGRESS,
                java.time.Instant.EPOCH, "session", "run", "request", "agent", new RunStatusPayload("RUNNING", id));
    }
}
