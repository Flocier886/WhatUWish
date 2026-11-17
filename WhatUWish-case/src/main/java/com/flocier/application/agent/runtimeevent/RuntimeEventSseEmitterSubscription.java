package com.flocier.application.agent.runtimeevent;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.event.RuntimeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** ResponseBodyEmitter 的 Runtime Event v1 SSE 适配器。 */
@Slf4j
public final class RuntimeEventSseEmitterSubscription implements RuntimeEventSubscriber, AutoCloseable {
    private final ResponseBodyEmitter emitter;
    private final RuntimeEventSessionRegistry registry;
    private final RuntimeEventScope scope;
    private final AtomicBoolean closed = new AtomicBoolean();
    private RuntimeEventSubscription subscription;
    private ScheduledFuture<?> heartbeat;

    private RuntimeEventSseEmitterSubscription(ResponseBodyEmitter emitter, RuntimeEventSessionRegistry registry, RuntimeEventScope scope) {
        this.emitter = emitter;
        this.registry = registry;
        this.scope = scope;
    }

    public static RuntimeEventSseEmitterSubscription open(ResponseBodyEmitter emitter,
                                                          RuntimeEventSessionRegistry registry,
                                                          RuntimeEventScope scope) {
        RuntimeEventSseEmitterSubscription adapter = new RuntimeEventSseEmitterSubscription(emitter, registry, scope);
        adapter.subscription = registry.subscribe(scope, adapter);
        emitter.onCompletion(adapter::close);
        emitter.onTimeout(adapter::close);
        emitter.onError(error -> adapter.close());
        adapter.heartbeat = registry.scheduleHeartbeat(adapter);
        return adapter;
    }

    @Override
    public void onEvent(RuntimeEvent event) throws Exception {
        if (closed.get()) return;
        emitter.send("id: " + event.getEventId() + "\n" + "event: runtime-event\n" + "data: " + JSON.toJSONString(event) + "\n\n");
        if (event.isTerminal()) close();
    }

    @Override
    public void onHeartbeat() throws Exception {
        if (!closed.get()) emitter.send(": heartbeat " + Instant.now() + "\n\n");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (heartbeat != null) heartbeat.cancel(false);
        if (subscription != null) subscription.close();
        try {
            emitter.complete();
        } catch (Exception e) {
        log.debug("runtime SSE completion ignored, runId={}, reason={}", scope.getRunId(), e.getMessage());
        }
    }
}
