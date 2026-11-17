package com.flocier.application.agent.imagegeneration.workbench;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationExecutionKernel;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationFinalResult;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationStreamEvent;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.TrustedLocalArtifactAccessAuthorizer;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionSource;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.infrastructure.artifact.LocalArtifactContentStoreBackend;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import com.flocier.infrastructure.artifact.RoutingArtifactContentWriter;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryImageGenerationExecutionRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 工作台必须直连共享图片内核，不能创建 Agent Runtime 事实。 */
public class WorkbenchImageGenerationExecutionServiceTest {

    @Test
    public void shouldCreateWorkbenchExecutionAndArtifactsWithoutRuntimeFacts() throws Exception {
        Fixture fixture = new Fixture(false, false);

        WorkbenchImageGenerationResult result = fixture.service.execute(WorkbenchImageGenerationCommand.builder()
                .sessionId("session-1")
                .prompt("把参考图改成雨夜城市")
                .mode("edits")
                .count(1)
                .references(List.of(WorkbenchImageInput.builder()
                        .fileName("source.png")
                        .declaredMimeType("image/png")
                        .content(png())
                        .build()))
                .masks(List.of())
                .build());

        Assert.assertEquals(
                "工作台生成失败，errorCode=" + result.getErrorCode() + ", errorMessage=" + result.getErrorMessage(),
                "completed",
                result.getStatus()
        );
        Assert.assertEquals(1, result.getArtifacts().size());
        Assert.assertEquals(1L, fixture.executions.countByExecutionSourceAndSession(
                ImageGenerationExecutionSource.WORKBENCH,
                "session-1"
        ));
        Assert.assertTrue(fixture.executions.findPageByExecutionSourceAndSession(
                ImageGenerationExecutionSource.WORKBENCH,
                "session-1",
                0,
                1
        ).get(0).getToolInvocationId() == null);
        Assert.assertEquals(2, fixture.artifacts.findByRunId(result.getExecutionId()).size());
        Assert.assertEquals(1, fixture.executions.findInputsByImageGenerationExecutionId(result.getExecutionId()).size());
        Assert.assertTrue(fixture.tools.findByRunId(result.getExecutionId()).isEmpty());
        Assert.assertTrue(fixture.runs.findByRunId(result.getExecutionId()).isEmpty());
    }

    @Test
    public void shouldRetainExecutionFailureWithoutCreatingToolInvocationOrRun() throws Exception {
        Fixture fixture = new Fixture(true, false);

        WorkbenchImageGenerationResult result = fixture.service.execute(WorkbenchImageGenerationCommand.builder()
                .sessionId("session-1")
                .prompt("生成一张海报")
                .mode("images")
                .references(List.of())
                .masks(List.of())
                .build());

        Assert.assertEquals("failed", result.getStatus());
        Assert.assertEquals("image_generation_execution_error", result.getErrorCode());
        Assert.assertEquals(1L, fixture.executions.countByExecutionSourceAndSession(
                ImageGenerationExecutionSource.WORKBENCH,
                "session-1"
        ));
        Assert.assertTrue(fixture.tools.findByRunId(result.getExecutionId()).isEmpty());
        Assert.assertTrue(fixture.runs.findByRunId(result.getExecutionId()).isEmpty());
        Assert.assertTrue(fixture.artifacts.findByRunId(result.getExecutionId()).isEmpty());
    }

    @Test
    public void shouldRetainRegisteredOutputWhenUpstreamFailsAfterArtifactCreated() throws Exception {
        Fixture fixture = new Fixture(false, true);

        WorkbenchImageGenerationResult result = fixture.service.execute(WorkbenchImageGenerationCommand.builder()
                .sessionId("session-1")
                .prompt("生成一张海报")
                .mode("images")
                .references(List.of())
                .masks(List.of())
                .build());

        Assert.assertEquals("failed", result.getStatus());
        Assert.assertEquals(1, result.getGeneratedCount().intValue());
        Assert.assertEquals(1, result.getArtifacts().size());
        Assert.assertEquals(1, fixture.artifacts.findByRunId(result.getExecutionId()).size());
        Assert.assertTrue(fixture.tools.findByRunId(result.getExecutionId()).isEmpty());
        Assert.assertTrue(fixture.runs.findByRunId(result.getExecutionId()).isEmpty());
    }

    @Test
    public void shouldRestoreOnlyWorkbenchHistoryAndDowngradeDeletedArtifact() throws Exception {
        Fixture fixture = new Fixture(false, false);
        WorkbenchImageGenerationResult result = fixture.service.execute(WorkbenchImageGenerationCommand.builder()
                .sessionId("session-1")
                .prompt("生成一张海报")
                .mode("images")
                .references(List.of())
                .masks(List.of())
                .build());
        fixture.executions.save(ImageGenerationExecutionEntity.builder()
                .imageGenerationExecutionId("runtime-execution")
                .executionSource(ImageGenerationExecutionSource.AGENT_RUNTIME)
                .toolInvocationId("runtime-tool")
                .sessionId("session-1")
                .status("completed")
                .startedAt(Instant.now().plusSeconds(1))
                .createdAt(Instant.now().plusSeconds(1))
                .updatedAt(Instant.now().plusSeconds(1))
                .versionNo(0L)
                .build());

        WorkbenchImageGenerationHistoryPage history = fixture.history.query("session-1", 1, 12);

        Assert.assertEquals(1L, history.getTotal());
        Assert.assertEquals(List.of(result.getExecutionId()), history.getItems().stream()
                .map(WorkbenchImageGenerationHistoryItem::getExecutionId).toList());
        Assert.assertEquals("AVAILABLE", history.getItems().get(0).getArtifacts().get(0).getAccessState());

        fixture.artifacts.findByArtifactId("output-1").orElseThrow().setDeleted(1);
        WorkbenchImageGenerationHistoryPage deletedHistory = fixture.history.query("session-1", 1, 12);

        Assert.assertEquals("NOT_FOUND", deletedHistory.getItems().get(0).getArtifacts().get(0).getAccessState());
        Assert.assertFalse(deletedHistory.getItems().get(0).getArtifacts().get(0).isPreviewSupported());
        Assert.assertFalse(deletedHistory.getItems().get(0).getArtifacts().get(0).isDownloadSupported());
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00};
    }

    private static final class Fixture {
        private final Path root;
        private final InMemoryArtifactLedgerRepository artifacts = new InMemoryArtifactLedgerRepository();
        private final InMemoryRunLedgerRepository runs = new InMemoryRunLedgerRepository();
        private final InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        private final InMemoryImageGenerationExecutionRepository executions = new InMemoryImageGenerationExecutionRepository();
        private final RoutingArtifactContentWriter writer;
        private final WorkbenchImageGenerationExecutionService service;
        private final WorkbenchImageGenerationHistoryService history;

        private Fixture(boolean shouldFail, boolean shouldFailAfterOutput) throws Exception {
            root = Files.createTempDirectory("workbench-image-");
            LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(root.toString());
            writer = new RoutingArtifactContentWriter(List.of(backend));
            LedgerRecordingService recording = new LedgerRecordingService(
                    new InMemorySessionLedgerRepository(),
                    runs,
                    new InMemoryLlmInvocationLedgerRepository(),
                    tools,
                    artifacts
            );
            ArtifactContentAccessService accessService = new ArtifactContentAccessService(
                    artifacts,
                    new TrustedLocalArtifactAccessAuthorizer(),
                    new RoutingArtifactContentStore(List.of(backend))
            );
            ImageGenerationExecutionKernel kernel = shouldFail
                    ? (pythonService, pythonEndpoint, request, consumer) -> {
                        throw new IllegalStateException("upstream failed");
                    }
                    : (pythonService, pythonEndpoint, request, consumer) -> generate(request, consumer, shouldFailAfterOutput);
            ReactorConfig reactorConfig = new ReactorConfig();
            reactorConfig.setToolConfigMap("{\"image_generation_tool\":{\"pythonService\":\"http://unused\",\"pythonEndpoint\":\"/unused\"}}");
            service = new WorkbenchImageGenerationExecutionService(
                    writer,
                    accessService,
                    recording,
                    executions,
                    kernel,
                    reactorConfig
            );
            history = new WorkbenchImageGenerationHistoryService(executions, artifacts, accessService);
            ReflectionTestUtils.setField(service, "writeStorageType", "local_artifact_storage");
            ReflectionTestUtils.setField(service, "writePrefix", "");
        }

        private ImageGenerationFinalResult generate(
                com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationStartRequest request,
                java.util.function.Consumer<ImageGenerationStreamEvent> consumer,
                boolean shouldFailAfterOutput
        ) {
            writer.write(com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest.builder()
                    .storageType("local_artifact_storage")
                    .storageKey("v1/artifacts/output-1/content")
                    .mimeType("image/png")
                    .content(png())
                    .build());
            consumer.accept(event(request.getExecutionId(), 1, "artifact_created", "running", Map.of("artifact", outputArtifact())));
            if (shouldFailAfterOutput) {
                throw new IllegalStateException("upstream failed after output");
            }
            consumer.accept(event(request.getExecutionId(), 2, "completed", "completed", Map.of("generatedCount", 1)));
            ImageGenerationFinalResult result = new ImageGenerationFinalResult();
            result.setStatus("completed");
            result.setSummary("图片生成完成");
            result.setMode("edits");
            result.setSize("1024x1024");
            result.setRequestedCount(1);
            result.setImageExecutionId("python-1");
            result.setArtifacts(List.of(outputArtifact()));
            return result;
        }

        private ImageGenerationStreamEvent event(String executionId, int sequence, String phase, String status, Map<String, Object> data) {
            ImageGenerationStreamEvent event = new ImageGenerationStreamEvent();
            event.setExecutionId(executionId);
            event.setSequence(sequence);
            event.setPhase(phase);
            event.setStatus(status);
            event.setOccurredAt(Instant.now());
            event.setSummary(phase);
            event.setData(new LinkedHashMap<>(data));
            return event;
        }

        private Map<String, Object> outputArtifact() {
            return Map.ofEntries(
                    Map.entry("artifactId", "output-1"),
                    Map.entry("name", "output.png"),
                    Map.entry("artifactType", "image"),
                    Map.entry("artifactRole", "output"),
                    Map.entry("visibility", "visible"),
                    Map.entry("sourceType", "python_artifact"),
                    Map.entry("sourceName", "image_generation_tool"),
                    Map.entry("fileName", "output.png"),
                    Map.entry("storageType", "local_artifact_storage"),
                    Map.entry("storageKey", "v1/artifacts/output-1/content"),
                    Map.entry("storageRef", "v1/artifacts/output-1/content"),
                    Map.entry("mimeType", "image/png"),
                    Map.entry("fileSize", 9),
                    Map.entry("fileHash", "sha256:output"),
                    Map.entry("contentSummary", "已生成图片"),
                    Map.entry("metadata", Map.of("preview", Map.of("kind", "image", "schemaVersion", "v1")))
            );
        }
    }
}
