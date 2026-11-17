package com.flocier.application.agent.runtimeevent;

import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** RuntimeEventPublisher 的实时会话实现。 */
@Component
@RequiredArgsConstructor
public class SessionRuntimeEventPublisher implements RuntimeEventPublisher {
    private final RuntimeEventSessionRegistry registry;

    @Override
    public void publish(RuntimeEventDraft draft) {
        registry.publish(draft);
    }
}
