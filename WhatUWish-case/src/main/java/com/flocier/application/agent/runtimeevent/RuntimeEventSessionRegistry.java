package com.flocier.application.agent.runtimeevent;

import com.flocier.domain.agent.runtime.event.RuntimeEvent;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 管理实时 Run 的 sequence、终态约束和订阅广播，不承担事实持久化。 */
@Slf4j
@Component
public class RuntimeEventSessionRegistry {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "runtime-event-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RuntimeEventSubscription subscribe(RuntimeEventScope scope, RuntimeEventSubscriber subscriber) {
        Session session = sessions.compute(scope.getRunId(), (runId, current) -> current == null ? new Session(scope) : current);
        session.validate(scope);
        session.subscribers.add(subscriber);
        return () -> unsubscribe(scope.getRunId(), subscriber);
    }

    public void publish(RuntimeEventDraft draft) {
        Session session = sessions.get(draft.getRunId());
        if (session == null) {
            log.debug("runtime event dropped because no live session exists, type={}, runId={}", draft.getEventType(), draft.getRunId());
            return;
        }
        session.publish(draft);
    }

    public java.util.concurrent.ScheduledFuture<?> scheduleHeartbeat(RuntimeEventSubscriber subscriber) {
        return scheduler.scheduleAtFixedRate(() -> {
            try {
                subscriber.onHeartbeat();
            } catch (Exception e) {
                log.debug("runtime event heartbeat skipped, reason={}", e.getMessage());
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void unsubscribe(String runId, RuntimeEventSubscriber subscriber) {
        Session session = sessions.get(runId);
        if (session == null) return;
        session.subscribers.remove(subscriber);
        if (session.subscribers.isEmpty()) {
            sessions.remove(runId, session);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        sessions.clear();
    }

    private final class Session {
        private final RuntimeEventScope scope;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final Set<RuntimeEventSubscriber> subscribers = ConcurrentHashMap.newKeySet();
        private final Set<String> publishedFacts = ConcurrentHashMap.newKeySet();

        private Session(RuntimeEventScope scope) { this.scope = scope; }

        private void validate(RuntimeEventScope candidate) {
            if (!scope.equals(candidate)) {
                throw new IllegalArgumentException("Runtime event scope conflicts with an existing run session");
            }
        }

        private synchronized void publish(RuntimeEventDraft draft) {
            if (!sameIdentity(draft)) {
                log.warn("runtime event identity mismatch, runId={}, eventType={}", draft.getRunId(), draft.getEventType());
                return;
            }
            if (terminal.get()) {
                log.debug("runtime event ignored after terminal state, runId={}, eventType={}", draft.getRunId(), draft.getEventType());
                return;
            }
            if (!isValidPersistedProgress(draft)) {
                log.warn("runtime tool progress rejected because stable identity is missing, runId={}", draft.getRunId());
                return;
            }
            String factKey = draft.factKey();
            if (factKey != null && !publishedFacts.add(factKey)) {
                log.debug("runtime event duplicate fact ignored, runId={}, factKey={}", draft.getRunId(), factKey);
                return;
            }
            long nextSequence = sequence.incrementAndGet();
            RuntimeEvent event = new RuntimeEvent(RuntimeEvent.SCHEMA_VERSION_V1, eventId(draft), nextSequence,
                    draft.getEventType(), draft.getOccurredAt() == null ? Instant.now() : draft.getOccurredAt(), draft.getSessionId(),
                    draft.getRunId(), draft.getRequestId(), draft.getAgentId(), draft.getPayload());
            if (event.isTerminal()) terminal.set(true);
            for (RuntimeEventSubscriber subscriber : subscribers) {
                try {
                    subscriber.onEvent(event);
                } catch (Exception e) {
                    log.info("runtime event subscriber closed, runId={}, eventId={}, reason={}", event.getRunId(), event.getEventId(), e.getMessage());
                    subscribers.remove(subscriber);
                }
            }
        }

        private boolean isValidPersistedProgress(RuntimeEventDraft draft) {
            if (draft.getEventType() != com.flocier.domain.agent.runtime.event.RuntimeEventType.TOOL_PROGRESS) {
                return true;
            }
            if (!(draft.getPayload() instanceof com.flocier.domain.agent.runtime.event.ToolProgressPayload payload)) {
                return false;
            }
            return draft.getEventId() != null
                    && !draft.getEventId().isBlank()
                    && draft.getEventId().equals(payload.getRuntimeEventId());
        }

        private String eventId(RuntimeEventDraft draft) {
            return draft.getEventId() == null || draft.getEventId().isBlank()
                    ? UUID.randomUUID().toString()
                    : draft.getEventId();
        }

        private boolean sameIdentity(RuntimeEventDraft draft) {
        return scope.getSessionId().equals(draft.getSessionId()) && scope.getRunId().equals(draft.getRunId())
                && scope.getRequestId().equals(draft.getRequestId()) && scope.getAgentId().equals(draft.getAgentId());
        }
    }
}
