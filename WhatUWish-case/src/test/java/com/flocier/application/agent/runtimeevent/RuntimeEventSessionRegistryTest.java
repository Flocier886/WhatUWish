package com.flocier.application.agent.runtimeevent;

import com.flocier.domain.agent.runtime.event.RunCompletedPayload;
import com.flocier.domain.agent.runtime.event.RunStartedPayload;
import com.flocier.domain.agent.runtime.event.RunStatusPayload;
import com.flocier.domain.agent.runtime.event.ReportContentDeltaPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEvent;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.event.ToolProgressPayload;
import com.flocier.domain.agent.runtime.event.ArtifactCreatedPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeEventSessionRegistryTest {

    @Test
    void assignsSequenceDeduplicatesFactsAndProtectsTerminalState() {
        RuntimeEventSessionRegistry registry = new RuntimeEventSessionRegistry();
        RuntimeEventScope scope = new RuntimeEventScope("session", "run", "request", "agent");
        List<RuntimeEvent> events = new ArrayList<>();
        registry.subscribe(scope, new RuntimeEventSubscriber() {
            @Override public void onEvent(RuntimeEvent event) { throw new IllegalStateException("simulated subscriber failure"); }
        });
        registry.subscribe(scope, new RuntimeEventSubscriber() {
            @Override public void onEvent(RuntimeEvent event) { events.add(event); }
        });

        registry.publish(draft(scope, RuntimeEventType.RUN_STARTED, new RunStartedPayload("RUNNING", "task")));
        registry.publish(draft(scope, RuntimeEventType.RUN_STARTED, new RunStartedPayload("RUNNING", "task")));
        registry.publish(draft(scope, RuntimeEventType.RUN_STATUS, new RunStatusPayload("RUNNING", "working")));
        registry.publish(draft(scope, RuntimeEventType.RUN_COMPLETED, new RunCompletedPayload("COMPLETED", "done", List.of(), null)));
        registry.publish(draft(scope, RuntimeEventType.RUN_STATUS, new RunStatusPayload("RUNNING", "late")));

        assertEquals(3, events.size());
        assertEquals(1L, events.get(0).getSequence());
        assertEquals(RuntimeEvent.SCHEMA_VERSION_V1, events.get(0).getSchemaVersion());
        assertEquals(2L, events.get(1).getSequence());
        assertEquals(3L, events.get(2).getSequence());
        assertEquals(RuntimeEventType.RUN_COMPLETED, events.get(2).getEventType());
    }

    @Test
    void mapsArtifactWithWhitelistOnly() {
        ArtifactLedgerEntity artifact = ArtifactLedgerEntity.builder()
                .artifactId("artifact-1").runId("run").sessionId("session").requestId("request")
                .artifactName("report.pdf").artifactType("file").sourceId("artifact:report")
                .visibility("visible").storageRef("internal/path").storageKey("secret-key")
                .downloadUrl("https://example.test/download").previewUrl("https://example.test/preview")
                .contentSummary("safe summary").createdAt(Instant.now()).build();

        ArtifactCreatedPayload payload = (ArtifactCreatedPayload) new RuntimeEventMapper()
                .artifactCreated(artifact, "agent").getPayload();

        String serialized = com.alibaba.fastjson.JSON.toJSONString(payload);
        assertEquals("artifact-1", payload.getArtifactId());
        assertEquals("VISIBLE", payload.getVisibility());
        assertFalse(serialized.contains("storageRef"));
        assertFalse(serialized.contains("storageKey"));
        assertFalse(serialized.contains("downloadUrl"));
        assertFalse(serialized.contains("previewUrl"));
    }

    @Test
    void preservesPersistedProgressIdentityAndDeduplicatesIt() {
        RuntimeEventSessionRegistry registry = new RuntimeEventSessionRegistry();
        RuntimeEventScope scope = new RuntimeEventScope("session", "run", "request", "agent");
        List<RuntimeEvent> events = new ArrayList<>();
        registry.subscribe(scope, new RuntimeEventSubscriber() {
            @Override
            public void onEvent(RuntimeEvent event) {
                events.add(event);
            }
        });
        ToolProgressPayload payload = new ToolProgressPayload(
                "persisted-fact-1", "tool-1", "deep_search_tool", 1,
                "search_completed", "completed", "search complete", null,
                "deep_search.search_results.v1", "1", java.util.Map.of()
        );
        RuntimeEventDraft draft = RuntimeEventDraft.persistedToolProgress(
                "persisted-fact-1", Instant.now(), "session", "run", "request", "agent", payload
        );

        registry.publish(draft);
        registry.publish(draft);

        assertEquals(1, events.size());
        assertEquals("persisted-fact-1", events.get(0).getEventId());
        assertEquals("persisted-fact-1", ((ToolProgressPayload) events.get(0).getPayload()).getRuntimeEventId());
    }

    @Test
    void publishesReportLiveDeltaWithTemporaryIdentityAndWithoutFactDeduplication() {
        RuntimeEventSessionRegistry registry = new RuntimeEventSessionRegistry();
        RuntimeEventScope scope = new RuntimeEventScope("session", "run", "request", "agent");
        List<RuntimeEvent> events = new ArrayList<>();
        registry.subscribe(scope, new RuntimeEventSubscriber() {
            @Override
            public void onEvent(RuntimeEvent event) {
                events.add(event);
            }
        });

        ReportContentDeltaPayload payload = new ReportContentDeltaPayload(
                "tool-report-1",
                "report_tool",
                "html",
                1,
                "<h1>仍是纯文本草稿</h1>"
        );
        RuntimeEventDraft draft = draft(scope, RuntimeEventType.REPORT_CONTENT_DELTA, payload);

        registry.publish(draft);
        registry.publish(draft);

        assertEquals(2, events.size());
        assertEquals(RuntimeEventType.REPORT_CONTENT_DELTA, events.get(0).getEventType());
        assertEquals(1L, events.get(0).getSequence());
        assertEquals(2L, events.get(1).getSequence());
        assertFalse(events.get(0).getEventId().isBlank());
        assertFalse(events.get(0).getEventId().equals(events.get(1).getEventId()));
        assertEquals(null, draft.factKey());
    }

    @Test
    void serializesConcurrentPublicationForOneRun() throws Exception {
        RuntimeEventSessionRegistry registry = new RuntimeEventSessionRegistry();
        RuntimeEventScope scope = new RuntimeEventScope("session", "run", "request", "agent");
        List<RuntimeEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        registry.subscribe(scope, new RuntimeEventSubscriber() {
            @Override public void onEvent(RuntimeEvent event) { events.add(event); }
        });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int index = 0; index < 2; index++) {
            final int eventNo = index;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(3, TimeUnit.SECONDS);
                    registry.publish(draft(scope, RuntimeEventType.RUN_STATUS,
                            new RunStatusPayload("RUNNING", "parallel-" + eventNo)));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertEquals(true, ready.await(3, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertEquals(true, executor.awaitTermination(3, TimeUnit.SECONDS));

        assertEquals(2, events.size());
        assertEquals(1L, events.get(0).getSequence());
        assertEquals(2L, events.get(1).getSequence());
    }

    private RuntimeEventDraft draft(RuntimeEventScope scope, RuntimeEventType type, RuntimeEventPayload payload) {
        return new RuntimeEventDraft(type, Instant.now(), scope.getSessionId(), scope.getRunId(), scope.getRequestId(), scope.getAgentId(), payload);
    }
}
