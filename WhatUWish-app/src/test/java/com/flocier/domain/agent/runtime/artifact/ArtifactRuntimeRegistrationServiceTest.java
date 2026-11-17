package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Rich Tool 运行中 Artifact 登记的定向回归。 */
public class ArtifactRuntimeRegistrationServiceTest {

    @Test
    public void shouldRegisterStableArtifactOnceAndPublishOnlyAfterCommit() {
        Fixture fixture = new Fixture();
        TransactionSynchronizationManager.initSynchronization();
        try {
            ArtifactRuntimeRegistrationResult first = fixture.service.registerAndPublish(fixture.request());

            Assert.assertTrue(first.isCreated());
            Assert.assertEquals("artifact-research", first.getArtifact().getArtifactId());
            Assert.assertEquals(1, fixture.artifacts.findByToolInvocationId("tool-invocation").size());
            Assert.assertEquals(1, fixture.context.requireArtifactRuntimeRegistry().listDeliverableOutputs().size());
            Assert.assertEquals("sha256:research", first.getArtifact().getFileHash());
            Assert.assertEquals("local_artifact_storage", first.getArtifact().getStorageType());
            Assert.assertTrue(first.getArtifact().getMetadataJson().contains("researchDepth"));
            Assert.assertTrue(fixture.published.isEmpty());

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            Assert.assertEquals(1, fixture.published.size());
            String payload = com.alibaba.fastjson.JSON.toJSONString(fixture.published.get(0).getPayload());
            Assert.assertFalse(payload.contains("storageKey"));
            Assert.assertFalse(payload.contains("storageRef"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArtifactRuntimeRegistrationResult duplicate = fixture.service.registerAndPublish(fixture.request());
        Assert.assertFalse(duplicate.isCreated());
        Assert.assertEquals(1, fixture.artifacts.findByToolInvocationId("tool-invocation").size());
        Assert.assertEquals(1, fixture.context.requireArtifactRuntimeRegistry().listDeliverableOutputs().size());
        Assert.assertEquals(1, fixture.published.size());
    }

    @Test
    public void materialBatchFailsWithoutPublishingPartialRuntimeBindings() {
        Fixture fixture = new Fixture();
        ArtifactRuntimeDescriptor valid = fixture.request().getDescriptor();
        ArtifactRuntimeDescriptor invalid = ArtifactRuntimeDescriptor.builder()
                .artifactId("invalid-without-storage").build();

        Assert.assertThrows(IllegalArgumentException.class, () ->
                fixture.service.registerBatchAndPublish(fixture.context, fixture.invocation,
                        fixture.source, List.of(valid, invalid)));
        Assert.assertTrue(fixture.artifacts.findByToolInvocationId("tool-invocation").isEmpty());
        Assert.assertTrue(fixture.context.requireArtifactRuntimeRegistry().listBindings().isEmpty());
        Assert.assertTrue(fixture.published.isEmpty());
    }

    private static final class Fixture {
        private final InMemoryArtifactLedgerRepository artifacts = new InMemoryArtifactLedgerRepository();
        private final List<RuntimeEventDraft> published = new ArrayList<>();
        private final AgentContext context = AgentContext.builder()
                .sessionId("session-1")
                .requestId("request-1")
                .artifactRuntimeRegistry(new ArtifactRuntimeRegistry())
                .build();
        private final ToolInvocationLedgerEntity invocation = ToolInvocationLedgerEntity.builder()
                .toolInvocationId("tool-invocation")
                .toolCallId("tool-call")
                .toolName("deep_search_tool")
                .runId("run-1")
                .sessionId("session-1")
                .requestId("request-1")
                .traceId("trace-1")
                .stepNo(2)
                .build();
        private final ArtifactRuntimeSource source = ArtifactRuntimeSource.builder()
                .runId("run-1")
                .sessionId("session-1")
                .requestId("request-1")
                .traceId("trace-1")
                .step(2)
                .toolCallId("tool-call")
                .toolName("deep_search_tool")
                .build();
        private final ArtifactRuntimeRegistrationService service;

        private Fixture() {
            context.setAttribute("agentId", "agent-1");
            LedgerRecordingService recording = new LedgerRecordingService(
                    new InMemorySessionLedgerRepository(),
                    new InMemoryRunLedgerRepository(),
                    new InMemoryLlmInvocationLedgerRepository(),
                    new InMemoryToolInvocationLedgerRepository(),
                    artifacts
            );
            ArtifactRuntimeLedgerRecorder recorder = new ArtifactRuntimeLedgerRecorder(recording,
                    new RuntimeEventMapper(), published::add);
            service = new ArtifactRuntimeRegistrationService(recorder, artifacts,
                    new RuntimeEventMapper(), published::add);
        }

        private ArtifactRuntimeRegistrationRequest request() {
            return ArtifactRuntimeRegistrationRequest.builder()
                    .context(context)
                    .toolInvocation(invocation)
                    .source(source)
                    .descriptor(ArtifactRuntimeDescriptor.builder()
                            .artifactId("artifact-research")
                            .name("research.md")
                            .artifactType("research_evidence_package")
                            .artifactRole("output")
                            .visibility("visible")
                            .sourceType("deep_search")
                            .sourceId("artifact-research")
                            .sourceName("deep_search_tool")
                            .fileName("research.md")
                            .storageType("local_artifact_storage")
                            .storageKey("artifacts/run-1/research.md")
                            .storageRef("artifacts/run-1/research.md")
                            .contentSummary("可交付研究证据包")
                            .mimeType("text/markdown")
                            .fileSize(128L)
                            .fileHash("sha256:research")
                            .metadata(Map.of("researchDepth", "standard"))
                            .build())
                    .build();
        }
    }
}
