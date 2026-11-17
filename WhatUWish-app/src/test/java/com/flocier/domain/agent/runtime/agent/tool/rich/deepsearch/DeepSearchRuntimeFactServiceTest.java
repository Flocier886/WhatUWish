package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistry;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeLedgerRecorder;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationService;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.ToolProgressPayload;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryDeepSearchExecutionRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRuntimeEventLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage 3 运行事实持久化的定向回归测试。 */
public class DeepSearchRuntimeFactServiceTest {

    @Test
    public void shouldPersistOrderedFactsRegisterArtifactAndPublishOnlyNewEvents() {
        Fixture fixture = new Fixture();
        fixture.start();

        fixture.service.accept(fixture.invocation, fixture.context, event(1, "research_started", "running", Map.of("researchDepth", "standard", "secret", "must-not-store")));
        fixture.service.accept(fixture.invocation, fixture.context, event(2, "artifact_created", "running", Map.of("artifact", artifact())));
        fixture.service.accept(fixture.invocation, fixture.context, event(3, "research_completed", "completed", Map.of("evidenceCount", 2, "rawBody", "must-not-store")));

        Assert.assertEquals(3, fixture.events.findByToolInvocationIdOrderBySequenceNo("invocation-1").size());
        Assert.assertEquals("artifact-1", fixture.events.findByToolInvocationIdOrderBySequenceNo("invocation-1").get(1).getArtifactId());
        Assert.assertFalse(fixture.events.findByToolInvocationIdOrderBySequenceNo("invocation-1").get(0).getPayloadJson().contains("secret"));
        Assert.assertEquals(1, fixture.artifacts.findByToolInvocationId("invocation-1").size());
        Assert.assertEquals(1, fixture.context.getArtifactRuntimeRegistry().listDeliverableOutputs().size());
        Assert.assertEquals(4, fixture.published.size());
        Assert.assertEquals("TOOL_PROGRESS", fixture.published.get(1).getEventType().name());
        Assert.assertEquals("ARTIFACT_CREATED", fixture.published.get(2).getEventType().name());
        Assert.assertEquals("artifact-1", ((com.flocier.domain.agent.runtime.event.ArtifactCreatedPayload)
                fixture.published.get(2).getPayload()).getArtifactId());
        Assert.assertEquals(Integer.valueOf(3), fixture.executions.findByToolInvocationId("invocation-1").orElseThrow().getLastSequenceNo());
    }

    @Test
    public void shouldIgnoreDuplicateAndRejectSequenceGapWithoutPublishing() {
        Fixture fixture = new Fixture();
        fixture.start();
        DeepSearchStreamEvent first = event(1, "research_started", "running", Map.of());
        fixture.service.accept(fixture.invocation, fixture.context, first);
        Assert.assertTrue(fixture.service.accept(fixture.invocation, fixture.context, first).duplicate());
        Assert.assertEquals(1, fixture.published.size());
        try {
            fixture.service.accept(fixture.invocation, fixture.context, event(3, "research_completed", "completed", Map.of()));
            Assert.fail("sequence gap must be rejected");
        } catch (DeepSearchStreamException exception) {
            Assert.assertEquals("deep_search_event_sequence_gap", exception.getErrorCode());
        }
        Assert.assertEquals(1, fixture.events.findByToolInvocationIdOrderBySequenceNo("invocation-1").size());
        Assert.assertEquals(1, fixture.published.size());
    }

    @Test
    public void shouldPersistAndPublishTheSameControlledSearchPresentation() {
        Fixture fixture = new Fixture();
        fixture.start();

        DeepSearchStreamEvent event = event(1, "search_completed", "running", Map.of());
        event.setPresentation(Map.of(
                "kind", "deep_search.search_results.v1",
                "schemaVersion", 1,
                "data", Map.of(
                        "iteration", 1,
                        "queries", List.of("runtime fact contract"),
                        "sources", List.of(Map.of(
                                "sourceId", "source-1",
                                "title", "Runtime fact guide",
                                "url", "https://example.com/guide",
                                "domain", "example.com",
                                "provider", "test",
                                "snippet", "controlled snippet",
                                "rawBody", "must-not-store"
                        ))
                )
        ));

        fixture.service.accept(fixture.invocation, fixture.context, event);

        String payloadJson = fixture.events
                .findByToolInvocationIdOrderBySequenceNo("invocation-1")
                .get(0)
                .getPayloadJson();
        ToolProgressPayload payload = (ToolProgressPayload) fixture.published.get(0).getPayload();

        Assert.assertEquals("deep_search.search_results.v1", payload.getPresentationKind());
        Assert.assertEquals(
                fixture.events.findByToolInvocationIdOrderBySequenceNo("invocation-1").get(0).getRuntimeEventId(),
                payload.getRuntimeEventId()
        );
        Assert.assertEquals("1", payload.getPresentationSchemaVersion());
        Assert.assertEquals(
                com.alibaba.fastjson.JSON.parseObject(payloadJson),
                com.alibaba.fastjson.JSON.parseObject(
                        com.alibaba.fastjson.JSON.toJSONString(payload.getPresentationData())
                )
        );
        Assert.assertTrue(payloadJson.contains("controlled snippet"));
        Assert.assertFalse(payloadJson.contains("rawBody"));
        Assert.assertFalse(payloadJson.contains("must-not-store"));
    }

    @Test
    public void shouldRejectUnsupportedPresentationAndNotPersistIt() {
        Fixture fixture = new Fixture();
        fixture.start();
        DeepSearchStreamEvent event = event(1, "search_completed", "running", Map.of());
        event.setPresentation(Map.of(
                "kind", "deep_search.unknown.v1",
                "schemaVersion", 1,
                "data", Map.of()
        ));

        try {
            fixture.service.accept(fixture.invocation, fixture.context, event);
            Assert.fail("unsupported presentation must be rejected");
        } catch (DeepSearchStreamException exception) {
            Assert.assertEquals("deep_search_presentation_invalid", exception.getErrorCode());
        }

        Assert.assertTrue(fixture.events.findByToolInvocationIdOrderBySequenceNo("invocation-1").isEmpty());
        Assert.assertTrue(fixture.published.isEmpty());
    }

    private static DeepSearchStreamEvent event(int sequence, String phase, String status, Map<String, Object> data) {
        DeepSearchStreamEvent event = new DeepSearchStreamEvent();
        event.setSchemaVersion("deep_search_event.v1");
        event.setEventId("evt-" + sequence);
        event.setExecutionId("python-1");
        event.setSequence(sequence);
        event.setPhase(phase);
        event.setStatus(status);
        event.setOccurredAt(Instant.parse("2026-08-05T06:00:0" + sequence + "Z"));
        event.setSummary("stage-" + sequence);
        event.setData(new LinkedHashMap<>(data));
        return event;
    }

    private static Map<String, Object> artifact() {
        return Map.ofEntries(Map.entry("artifactId", "artifact-1"), Map.entry("name", "Evidence package"),
                Map.entry("artifactType", "research_evidence_package"), Map.entry("fileName", "evidence.md"),
                Map.entry("artifactRole", "output"), Map.entry("visibility", "visible"),
                Map.entry("sourceType", "deep_search"), Map.entry("sourceName", "deep_search_tool"),
                Map.entry("storageType", "file_ref"), Map.entry("storageKey", "research/artifact-1.md"),
                Map.entry("storageRef", "research/artifact-1.md"), Map.entry("contentSummary", "controlled summary"),
                Map.entry("mimeType", "text/markdown"), Map.entry("fileSize", 12), Map.entry("fileHash", "sha256:test"));
    }

    private static class Fixture {
        final InMemoryRuntimeEventLedgerRepository events = new InMemoryRuntimeEventLedgerRepository();
        final InMemoryDeepSearchExecutionRepository executions = new InMemoryDeepSearchExecutionRepository();
        final InMemoryArtifactLedgerRepository artifacts = new InMemoryArtifactLedgerRepository();
        final List<RuntimeEventDraft> published = new ArrayList<>();
        final ArtifactRuntimeRegistrationService artifactRegistrationService = new ArtifactRuntimeRegistrationService(
                new ArtifactRuntimeLedgerRecorder(new LedgerRecordingService(
                        new InMemorySessionLedgerRepository(), new InMemoryRunLedgerRepository(),
                        new InMemoryLlmInvocationLedgerRepository(), new InMemoryToolInvocationLedgerRepository(), artifacts
                ), new RuntimeEventMapper(), published::add),
                artifacts, new RuntimeEventMapper(), published::add
        );
        final RichRuntimeProgressRecordingService richProgressRecordingService = new RichRuntimeProgressRecordingService(events, published::add);
        final DeepSearchRuntimeFactService service = new DeepSearchRuntimeFactService(
                executions,
                artifactRegistrationService,
                richProgressRecordingService,
                new DeepSearchPresentationMapper()
        );
        final ToolInvocationLedgerEntity invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1").toolCallId("call-1").toolName("deep_search_tool").runId("run-1").sessionId("session-1").requestId("request-1").traceId("trace-1").build();
        final AgentContext context = AgentContext.builder().sessionId("session-1").requestId("request-1")
                .artifactRuntimeRegistry(new ArtifactRuntimeRegistry()).build();
        void start() { context.setAttribute("agentId", "agent-1"); service.start(invocation, DeepSearchStartRequest.from(com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest.builder().arguments(Map.of("topic", "runtime facts")).build(), invocation.getToolInvocationId())); }
    }
}
