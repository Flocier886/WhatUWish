package com.flocier.test.domain.ledger;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.model.entity.ArtifactDeliveryFileEntity;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeBinding;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.domain.agent.runtime.event.ArtifactCreatedPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeReplayEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeReplayProjection;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.replay.ArtifactReplayEntry;
import com.flocier.domain.ledger.model.replay.ReplayEntryMetadata;
import com.flocier.domain.ledger.model.replay.RunReplayTimeline;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

public class ArtifactDeliveryProjectionConsistencyTest {

    @Test
    public void shouldKeepLiveFinalAndReplayDeliveryIdentityAndCapabilitiesConsistent() {
        ArtifactLedgerEntity ledger = ledgerArtifact();
        ArtifactCreatedPayload live = (ArtifactCreatedPayload) new RuntimeEventMapper()
                .artifactCreated(ledger, "agent-1").getPayload();
        ArtifactReplayEntry replayEntry = new ArtifactReplayEntry(
                new ReplayEntryMetadata("artifact:artifact-1", 1, Instant.EPOCH, "run-1", false),
                "llm-1", "tool-1", "artifact-1", "evidence.md", "research_evidence_package",
                "text/markdown", 128L, "safe summary", "visible", "tool-1", "deep_search_tool",
                ArtifactAccessState.AVAILABLE.name(), true, true);
        RuntimeReplayProjection replayProjection = new RuntimeReplayEventMapper().project(run(),
                RunReplayTimeline.builder().runId("run-1").sessionId("session-1")
                        .entries(List.of(replayEntry)).build());
        ArtifactCreatedPayload replay = (ArtifactCreatedPayload) replayProjection.getEvents().get(0).getPayload();
        ArtifactDeliveryFileEntity finalFile = ArtifactDeliveryFileEntity.from(runtimeBinding());

        Assert.assertEquals(live.getArtifactId(), replay.getArtifactId());
        Assert.assertEquals(live.getArtifactId(), finalFile.getArtifactId());
        Assert.assertEquals("run-1", replayProjection.getEvents().get(0).getRunId());
        Assert.assertEquals("run-1", finalFile.getRunId());
        Assert.assertEquals(live.getName(), replay.getName());
        Assert.assertEquals(live.getName(), finalFile.getName());
        Assert.assertEquals(live.getArtifactType(), replay.getArtifactType());
        Assert.assertEquals(live.getArtifactType(), finalFile.getArtifactType());
        Assert.assertEquals(live.getMimeType(), replay.getMimeType());
        Assert.assertEquals(live.getMimeType(), finalFile.getMimeType());
        Assert.assertEquals(live.getSize(), replay.getSize());
        Assert.assertEquals(live.getSize(), finalFile.getSize());
        Assert.assertEquals(live.getSummary(), replay.getSummary());
        Assert.assertEquals(live.getSummary(), finalFile.getSummary());
        Assert.assertEquals(live.getSourceStage(), replay.getSourceStage());
        Assert.assertEquals(live.getSourceStage(), finalFile.getSourceStage());
        Assert.assertEquals(live.getAccessState(), replay.getAccessState());
        Assert.assertEquals(live.getAccessState(), finalFile.getAccessState().name());
        Assert.assertEquals(live.isPreviewSupported(), replay.isPreviewSupported());
        Assert.assertEquals(live.isPreviewSupported(), finalFile.isPreviewSupported());
        Assert.assertEquals(live.getPreview(), replay.getPreview());
        Assert.assertEquals(live.getPreview(), finalFile.getPreview());
        Assert.assertEquals("markdown", live.getPreview().getKind());
        Assert.assertEquals("v1", live.getPreview().getSchemaVersion());
        Assert.assertEquals(live.isDownloadSupported(), replay.isDownloadSupported());
        Assert.assertEquals(live.isDownloadSupported(), finalFile.isDownloadSupported());

        String publicJson = JSON.toJSONString(List.of(live, replay, finalFile));
        Assert.assertFalse(publicJson.contains("storageKey"));
        Assert.assertFalse(publicJson.contains("storageRef"));
        Assert.assertFalse(publicJson.contains("private/artifacts"));
        Assert.assertFalse(publicJson.contains("downloadUrl"));
        Assert.assertFalse(publicJson.contains("previewUrl"));
    }

    @Test
    public void shouldKeepDeletedArtifactFactButDisableReplayFileActions() {
        ArtifactReplayEntry deleted = new ArtifactReplayEntry(
                new ReplayEntryMetadata("artifact:artifact-deleted", 1, Instant.EPOCH, "run-1", false),
                null, "tool-1", "artifact-deleted", "deleted.md", "report", "text/markdown",
                12L, "historical fact", "visible", "tool-1", "deep_search_tool",
                ArtifactAccessState.NOT_FOUND.name(), false, false);

        ArtifactCreatedPayload payload = (ArtifactCreatedPayload) new RuntimeReplayEventMapper().project(run(),
                RunReplayTimeline.builder().runId("run-1").sessionId("session-1")
                        .entries(List.of(deleted)).build()).getEvents().get(0).getPayload();

        Assert.assertEquals("NOT_FOUND", payload.getAccessState());
        Assert.assertFalse(payload.isPreviewSupported());
        Assert.assertFalse(payload.isDownloadSupported());
        Assert.assertNull(payload.getPreview());
    }

    @Test
    public void shouldKeepExplicitImagePreviewAcrossLiveFinalAndReplayWithoutStorageFields() {
        ArtifactLedgerEntity ledger = ledgerArtifact();
        ledger.setArtifactId("image-1");
        ledger.setArtifactName("generated.png");
        ledger.setFileName("generated.png");
        ledger.setArtifactType("image");
        ledger.setMimeType("image/png");
        ledger.setMetadataJson("{\"preview\":{\"kind\":\"image\",\"schemaVersion\":\"v1\"}}");
        ArtifactCreatedPayload live = (ArtifactCreatedPayload) new RuntimeEventMapper()
                .artifactCreated(ledger, "agent-1").getPayload();
        ArtifactReplayEntry replayEntry = new ArtifactReplayEntry(
                new ReplayEntryMetadata("artifact:image-1", 1, Instant.EPOCH, "run-1", false),
                "llm-1", "tool-1", "image-1", "generated.png", "image", "image/png", 128L,
                "safe image summary", "visible", "tool-1", "image_generation_tool",
                ArtifactAccessState.AVAILABLE.name(), true, true,
                ArtifactPreviewDescriptor.imageV1());
        ArtifactCreatedPayload replay = (ArtifactCreatedPayload) new RuntimeReplayEventMapper().project(run(),
                RunReplayTimeline.builder().runId("run-1").sessionId("session-1")
                        .entries(List.of(replayEntry)).build()).getEvents().get(0).getPayload();
        ArtifactRuntimeBinding binding = runtimeBinding();
        binding.getDescriptor().setArtifactId("image-1");
        binding.getDescriptor().setName("generated.png");
        binding.getDescriptor().setFileName("generated.png");
        binding.getDescriptor().setArtifactType("image");
        binding.getDescriptor().setMimeType("image/png");
        binding.getDescriptor().setMetadata(java.util.Map.of("preview", java.util.Map.of("kind", "image", "schemaVersion", "v1")));
        ArtifactDeliveryFileEntity finalFile = ArtifactDeliveryFileEntity.from(binding);

        Assert.assertEquals(ArtifactPreviewDescriptor.imageV1(), live.getPreview());
        Assert.assertEquals(ArtifactPreviewDescriptor.imageV1(), replay.getPreview());
        Assert.assertEquals(ArtifactPreviewDescriptor.imageV1(), finalFile.getPreview());
        Assert.assertTrue(live.isPreviewSupported());
        Assert.assertTrue(replay.isPreviewSupported());
        Assert.assertTrue(finalFile.isPreviewSupported());
        String publicJson = JSON.toJSONString(List.of(live, replay, finalFile));
        Assert.assertFalse(publicJson.contains("storageKey"));
        Assert.assertFalse(publicJson.contains("storageRef"));
        Assert.assertFalse(publicJson.contains("private/artifacts"));
    }

    private ArtifactLedgerEntity ledgerArtifact() {
        return ArtifactLedgerEntity.builder().artifactId("artifact-1").runId("run-1").sessionId("session-1")
                .requestId("request-1").toolInvocationId("tool-1").artifactRole("output")
                .visibility("visible").sourceType("deep_search").sourceId("artifact-1")
                .sourceName("deep_search_tool").artifactType("research_evidence_package")
                .artifactName("evidence.md").fileName("evidence.md").storageType("file_ref")
                .storageKey("private/artifacts/evidence.md").storageRef("private/artifacts/evidence.md")
                .contentSummary("safe summary").mimeType("text/markdown").fileSize(128L).deleted(0)
                .createdAt(Instant.EPOCH).build();
    }

    private ArtifactRuntimeBinding runtimeBinding() {
        ArtifactRuntimeSource source = ArtifactRuntimeSource.builder().runId("run-1").sessionId("session-1")
                .requestId("request-1").toolCallId("call-1").toolName("deep_search_tool").build();
        ArtifactRuntimeDescriptor descriptor = ArtifactRuntimeDescriptor.builder().artifactId("artifact-1")
                .name("evidence.md").fileName("evidence.md").artifactType("research_evidence_package")
                .artifactRole("output").visibility("visible").sourceName("deep_search_tool")
                .storageType("file_ref").storageKey("private/artifacts/evidence.md")
                .storageRef("private/artifacts/evidence.md").contentSummary("safe summary")
                .mimeType("text/markdown").fileSize(128L).build();
        return ArtifactRuntimeBinding.builder().source(source).descriptor(descriptor).build();
    }

    private RunLedgerEntity run() {
        return RunLedgerEntity.builder().runId("run-1").sessionId("session-1").requestId("request-1")
                .agentId("agent-1").status("RUNNING").startedAt(Instant.EPOCH).build();
    }
}
