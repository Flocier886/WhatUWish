package com.flocier.domain.ledger.service.replay;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按工具名选择回放投影器，默认投影器始终兜底。 */
@Component
@RequiredArgsConstructor
public class ToolReplayProjectorRegistry {
    private final List<ToolReplayProjector> projectors;
    private final DefaultToolReplayProjector defaultProjector;

    public ToolReplayProjector resolve(String toolName) {
        return projectors.stream()
                .filter(projector -> projector != defaultProjector && projector.supports(toolName))
                .findFirst()
                .orElse(defaultProjector);
    }
}
