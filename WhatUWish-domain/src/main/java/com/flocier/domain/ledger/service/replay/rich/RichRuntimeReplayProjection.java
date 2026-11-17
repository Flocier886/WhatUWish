package com.flocier.domain.ledger.service.replay.rich;

import com.flocier.domain.agent.runtime.event.RuntimeEvent;
import lombok.Value;

import java.util.List;

/** 一个或多个 Rich Tool Projector 合并后的通用过程回放。 */
@Value
public class RichRuntimeReplayProjection {
    List<RuntimeEvent> events;
    boolean dataIncomplete;

    public static RichRuntimeReplayProjection empty() {
        return new RichRuntimeReplayProjection(List.of(), false);
    }
}
