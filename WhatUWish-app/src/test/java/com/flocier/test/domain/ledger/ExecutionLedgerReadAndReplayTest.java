package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.ArtifactReplayResolver;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.model.execution.ExecutionLedgerView;
import com.flocier.domain.ledger.model.execution.ToolLlmAssociation;
import com.flocier.domain.ledger.model.replay.ArtifactReplayEntry;
import com.flocier.domain.ledger.model.replay.CompletedReplayEntry;
import com.flocier.domain.ledger.model.replay.ToolReplayEntry;
import com.flocier.domain.ledger.service.ExecutionLedgerReadService;
import com.flocier.domain.ledger.service.ExecutionReplayService;
import com.flocier.domain.ledger.service.replay.DefaultToolReplayProjector;
import com.flocier.domain.ledger.service.replay.ReplayProjector;
import com.flocier.domain.ledger.service.replay.ToolReplayProjectorRegistry;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ExecutionLedgerReadAndReplayTest {

    @Test
    public void shouldReadPersistedInferredAndOrphanToolAssociationsInStableOrder() {
        Fixture fixture = new Fixture();
        fixture.runRepository.save(run("run-association"));
        fixture.llmRepository.save(llm("llm-parent", "run-association", "call-inferred"));
        fixture.toolRepository.save(tool("tool-persisted", "run-association", "call-persisted", "llm-parent", 1));
        fixture.toolRepository.save(tool("tool-inferred", "run-association", "call-inferred", null, 2));
        fixture.toolRepository.save(tool("tool-orphan", "run-association", "call-orphan", null, 3));

        ExecutionLedgerView view = fixture.readService.readRun("run-association");

        Assert.assertEquals(1, view.getRuns().size());
        Assert.assertEquals(2, view.getRuns().get(0).getLlmCycles().get(0).getToolInvocations().size());
        Assert.assertEquals(ToolLlmAssociation.PERSISTED,
                view.getRuns().get(0).getLlmCycles().get(0).getToolInvocations().get(0).getAssociation());
        Assert.assertEquals(ToolLlmAssociation.INFERRED,
                view.getRuns().get(0).getLlmCycles().get(0).getToolInvocations().get(1).getAssociation());
        Assert.assertEquals(1, view.getRuns().get(0).getOrphanToolInvocations().size());
        Assert.assertEquals(ToolLlmAssociation.ORPHAN,
                view.getRuns().get(0).getOrphanToolInvocations().get(0).getAssociation());
    }

    @Test
    public void shouldProjectVisibleArtifactWithoutReadingArtifactBody() {
        Fixture fixture = new Fixture();
        fixture.runRepository.save(run("run-replay"));
        fixture.llmRepository.save(llm("llm-replay", "run-replay", "call-replay"));
        fixture.toolRepository.save(tool("tool-replay", "run-replay", "call-replay", "llm-replay", 0));
        fixture.artifactRepository.save(ArtifactLedgerEntity.builder()
                .artifactId("artifact-replay")
                .runId("run-replay")
                .toolInvocationId("tool-replay")
                .toolCallId("call-replay")
                .artifactRole("output")
                .visibility("visible")
                .artifactType("file")
                .artifactName("result.txt")
                .storageType("file_ref")
                .storageRef("D:/artifacts/result.txt")
                .contentSummary("可按需读取的结果文件")
                .createdAt(Instant.now())
                .build());

        var bundle = fixture.replayService.replayRun("run-replay");

        Assert.assertTrue(bundle.getEntries().stream().anyMatch(ToolReplayEntry.class::isInstance));
        Assert.assertTrue(bundle.getEntries().stream().filter(ArtifactReplayEntry.class::isInstance)
                .map(ArtifactReplayEntry.class::cast).anyMatch(item -> "artifact-replay".equals(item.getArtifactId())));
        Assert.assertTrue(bundle.getEntries().stream().anyMatch(CompletedReplayEntry.class::isInstance));
    }

    @Test
    public void shouldKeepSoftDeletedArtifactAsUnavailableReplayFact() {
        Fixture fixture = new Fixture();
        fixture.runRepository.save(run("run-deleted-replay"));
        fixture.artifactRepository.save(ArtifactLedgerEntity.builder()
                .artifactId("artifact-deleted-replay")
                .runId("run-deleted-replay")
                .artifactRole("output")
                .visibility("visible")
                .artifactType("file")
                .artifactName("deleted.md")
                .fileName("deleted.md")
                .mimeType("text/markdown")
                .storageType("file_ref")
                .storageRef("D:/artifacts/deleted.md")
                .deleted(1)
                .createdAt(Instant.now())
                .build());

        ArtifactReplayEntry artifact = fixture.replayService.replayRun("run-deleted-replay").getEntries().stream()
                .filter(ArtifactReplayEntry.class::isInstance)
                .map(ArtifactReplayEntry.class::cast)
                .findFirst()
                .orElseThrow();

        Assert.assertEquals("artifact-deleted-replay", artifact.getArtifactId());
        Assert.assertEquals(ArtifactAccessState.NOT_FOUND.name(), artifact.getAccessState());
        Assert.assertFalse(artifact.isPreviewSupported());
        Assert.assertFalse(artifact.isDownloadSupported());
    }

    @Test
    public void shouldKeepPhysicallyUnavailableArtifactAsUnavailableReplayFact() {
        Fixture fixture = new Fixture();
        fixture.runRepository.save(run("run-missing-replay"));
        fixture.artifactRepository.save(ArtifactLedgerEntity.builder()
                .artifactId("artifact-missing-replay")
                .runId("run-missing-replay")
                .artifactRole("output")
                .visibility("visible")
                .artifactType("image")
                .artifactName("missing.png")
                .fileName("missing.png")
                .mimeType("image/png")
                .storageType("s3")
                .storageKey("missing/replay.png")
                .storageRef("missing/replay.png")
                .metadataJson("{\"preview\":{\"kind\":\"image\",\"schemaVersion\":\"v1\"}}")
                .createdAt(Instant.now())
                .build());

        ArtifactReplayEntry artifact = fixture.replayService.replayRun("run-missing-replay").getEntries().stream()
                .filter(ArtifactReplayEntry.class::isInstance)
                .map(ArtifactReplayEntry.class::cast)
                .findFirst()
                .orElseThrow();

        Assert.assertEquals(ArtifactAccessState.UNAVAILABLE.name(), artifact.getAccessState());
        Assert.assertFalse(artifact.isPreviewSupported());
        Assert.assertFalse(artifact.isDownloadSupported());
    }

    private static RunLedgerEntity run(String runId) {
        Instant now = Instant.now();
        return RunLedgerEntity.builder().runId(runId).sessionId("session-replay").status("COMPLETED")
                .summary("已完成，产物见 artifact").startedAt(now).finishedAt(now).createdAt(now).updatedAt(now).build();
    }

    private static LlmInvocationLedgerEntity llm(String id, String runId, String declaredToolCallId) {
        Instant now = Instant.now();
        return LlmInvocationLedgerEntity.builder().llmInvocationId(id).runId(runId).sessionId("session-replay")
                .responseSummary("模型决定调用工具")
                .responsePayloadJson("{\"metadata\":{\"toolCalls\":[{\"id\":\"" + declaredToolCallId + "\"}]}}")
                .startedAt(now).finishedAt(now).createdAt(now).updatedAt(now).build();
    }

    private static ToolInvocationLedgerEntity tool(String id, String runId, String callId, String parentId, int dispatchOrder) {
        Instant now = Instant.now();
        return ToolInvocationLedgerEntity.builder().toolInvocationId(id).runId(runId).sessionId("session-replay")
                .toolCallId(callId).llmInvocationId(parentId).dispatchOrder(dispatchOrder).toolName("test_tool")
                .status("SUCCESS").requestPayloadJson("{\"arguments\":{\"query\":\"test\"}}")
                .responsePayloadJson("{\"llmObservation\":\"工具已完成\"}")
                .startedAt(now).finishedAt(now).createdAt(now).updatedAt(now).build();
    }

    private static class Fixture {
        private final InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        private final InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        private final InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        private final InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        private final ExecutionLedgerReadService readService = new ExecutionLedgerReadService(
                runRepository, llmRepository, toolRepository, artifactRepository);
        private final DefaultToolReplayProjector defaultProjector = new DefaultToolReplayProjector();
        private final ArtifactContentStore artifactContentStore = new ArtifactContentStore() {
            @Override
            public ArtifactStoredContent inspect(ArtifactContentLocator locator) {
                if (locator == null || locator.preferredKey().startsWith("missing/")) {
                    throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
                }
                return new ArtifactStoredContent(1L);
            }

            @Override
            public java.io.InputStream openDownload(ArtifactContentLocator locator) {
                throw new UnsupportedOperationException("不应在回放阶段读取 Artifact 内容");
            }

            @Override
            public byte[] readPreview(ArtifactContentLocator locator, int maxBytes) {
                throw new UnsupportedOperationException("不应在回放阶段读取 Artifact 内容");
            }
        };
        private final ReplayProjector replayProjector = new ReplayProjector(
                new ToolReplayProjectorRegistry(List.of(defaultProjector), defaultProjector), new ArtifactReplayResolver(),
                artifactContentStore);
        private final ExecutionReplayService replayService = new ExecutionReplayService(readService, replayProjector);
    }
}
