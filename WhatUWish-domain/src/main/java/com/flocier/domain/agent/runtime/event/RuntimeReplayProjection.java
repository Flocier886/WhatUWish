package com.flocier.domain.agent.runtime.event;

import java.util.List;
import lombok.Getter;

/** 历史回放的受控展示投影，dataIncomplete 用于提示旧账本事实缺失。 */
@Getter
public final class RuntimeReplayProjection {

    private final List<RuntimeEvent> events;
    private final boolean dataIncomplete;

    public RuntimeReplayProjection(List<RuntimeEvent> events, boolean dataIncomplete) {
        this.events = events;
        this.dataIncomplete = dataIncomplete;
    }
}
