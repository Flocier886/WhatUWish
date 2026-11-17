package com.flocier.application.agent.runtimeevent;

import com.flocier.domain.agent.runtime.event.RuntimeEvent;

public interface RuntimeEventSubscriber {
    void onEvent(RuntimeEvent event) throws Exception;

    default void onHeartbeat() throws Exception { }
}
