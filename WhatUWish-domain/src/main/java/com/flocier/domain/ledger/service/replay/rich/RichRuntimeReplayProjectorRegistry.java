package com.flocier.domain.ledger.service.replay.rich;

import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 汇总已注册 Rich Tool 的回放投影，History 不依赖任何具体工具。 */
@Component
public class RichRuntimeReplayProjectorRegistry {
    private final List<RichRuntimeReplayProjector> projectors;

    public RichRuntimeReplayProjectorRegistry(List<RichRuntimeReplayProjector> projectors) {
        this.projectors = projectors == null ? List.of() : List.copyOf(projectors);
    }

    public RichRuntimeReplayProjection project(RunLedgerEntity run) {
        List<com.flocier.domain.agent.runtime.event.RuntimeEvent> events = new ArrayList<>();
        boolean incomplete = false;
        for (RichRuntimeReplayProjector projector : projectors) {
            if (projector == null) continue;
            RichRuntimeReplayProjection projection = projector.project(run);
            if (projection == null) continue;
            events.addAll(projection.getEvents());
            incomplete |= projection.isDataIncomplete();
        }
        return new RichRuntimeReplayProjection(List.copyOf(events), incomplete);
    }
}
