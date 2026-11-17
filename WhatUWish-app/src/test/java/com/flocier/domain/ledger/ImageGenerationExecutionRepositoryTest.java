package com.flocier.domain.ledger;

import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionInputArtifactEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionSource;
import com.flocier.infrastructure.persistent.memory.InMemoryImageGenerationExecutionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** 图片执行来源、工作台分页和输入 Artifact 有序关系的离线 Repository 契约。 */
public class ImageGenerationExecutionRepositoryTest {

    @Test
    public void shouldKeepRuntimeExecutionCompatibleAndAllowMultipleWorkbenchExecutionsWithoutToolInvocation() {
        InMemoryImageGenerationExecutionRepository repository = new InMemoryImageGenerationExecutionRepository();
        repository.save(execution(
                "runtime-legacy",
                ImageGenerationExecutionSource.AGENT_RUNTIME,
                "tool-invocation-1",
                "session-1",
                "2026-08-31T09:00:00Z"
        ));
        repository.save(execution(
                "workbench-1",
                ImageGenerationExecutionSource.WORKBENCH,
                null,
                "session-1",
                "2026-08-31T10:00:00Z"
        ));
        repository.save(execution(
                "workbench-2",
                ImageGenerationExecutionSource.WORKBENCH,
                null,
                "session-1",
                "2026-08-31T11:00:00Z"
        ));

        Assert.assertEquals(
                "runtime-legacy",
                repository.findByToolInvocationId("tool-invocation-1").orElseThrow().getImageGenerationExecutionId()
        );
        Assert.assertEquals(2L, repository.countByExecutionSourceAndSession(
                ImageGenerationExecutionSource.WORKBENCH,
                "session-1"
        ));
        Assert.assertEquals(
                List.of("workbench-2", "workbench-1"),
                repository.findPageByExecutionSourceAndSession(
                                ImageGenerationExecutionSource.WORKBENCH,
                                "session-1",
                                0,
                                10
                        )
                        .stream()
                        .map(ImageGenerationExecutionEntity::getImageGenerationExecutionId)
                        .toList()
        );
    }

    @Test
    public void shouldIsolateOriginAndUseStableTimeThenExecutionIdPagination() {
        InMemoryImageGenerationExecutionRepository repository = new InMemoryImageGenerationExecutionRepository();
        repository.save(execution(
                "runtime-1",
                ImageGenerationExecutionSource.AGENT_RUNTIME,
                "tool-invocation-1",
                "session-1",
                "2026-08-31T12:00:00Z"
        ));
        repository.save(execution(
                "workbench-a",
                ImageGenerationExecutionSource.WORKBENCH,
                null,
                "session-1",
                "2026-08-31T12:00:00Z"
        ));
        repository.save(execution(
                "workbench-b",
                ImageGenerationExecutionSource.WORKBENCH,
                null,
                "session-1",
                "2026-08-31T12:00:00Z"
        ));
        repository.save(execution(
                "workbench-other-session",
                ImageGenerationExecutionSource.WORKBENCH,
                null,
                "session-2",
                "2026-08-31T13:00:00Z"
        ));

        List<ImageGenerationExecutionEntity> firstPage = repository.findPageByExecutionSourceAndSession(
                ImageGenerationExecutionSource.WORKBENCH,
                "session-1",
                0,
                1
        );
        List<ImageGenerationExecutionEntity> secondPage = repository.findPageByExecutionSourceAndSession(
                ImageGenerationExecutionSource.WORKBENCH,
                "session-1",
                1,
                1
        );

        Assert.assertEquals("workbench-b", firstPage.get(0).getImageGenerationExecutionId());
        Assert.assertEquals("workbench-a", secondPage.get(0).getImageGenerationExecutionId());
        Assert.assertEquals(2L, repository.countByExecutionSourceAndSession(
                ImageGenerationExecutionSource.WORKBENCH,
                "session-1"
        ));
    }

    @Test
    public void shouldKeepReferenceAndMaskInputsOrderedForWorkbenchExecution() {
        InMemoryImageGenerationExecutionRepository repository = new InMemoryImageGenerationExecutionRepository();
        repository.save(execution(
                "workbench-inputs",
                ImageGenerationExecutionSource.WORKBENCH,
                null,
                "session-1",
                "2026-08-31T12:00:00Z"
        ));
        repository.saveInputArtifacts(List.of(
                input("workbench-inputs", "mask-2", "mask", 1),
                input("workbench-inputs", "reference-2", "reference", 1),
                input("workbench-inputs", "mask-1", "mask", 0),
                input("workbench-inputs", "reference-1", "reference", 0)
        ));

        Assert.assertEquals(
                List.of("mask-1", "mask-2", "reference-1", "reference-2"),
                repository.findInputsByImageGenerationExecutionId("workbench-inputs")
                        .stream()
                        .map(ImageGenerationExecutionInputArtifactEntity::getArtifactId)
                        .toList()
        );
    }

    private static ImageGenerationExecutionEntity execution(
            String executionId,
            String source,
            String toolInvocationId,
            String sessionId,
            String startedAt
    ) {
        Instant time = Instant.parse(startedAt);
        return ImageGenerationExecutionEntity.builder()
                .imageGenerationExecutionId(executionId)
                .executionSource(source)
                .toolInvocationId(toolInvocationId)
                .runId("AGENT_RUNTIME".equals(source) ? "run-1" : null)
                .sessionId(sessionId)
                .requestId("request-1")
                .generationMode("images")
                .requestedOutputCount(1)
                .generatedOutputCount(0)
                .referenceArtifactCount(0)
                .maskArtifactCount(0)
                .usedFallback(false)
                .status("running")
                .startedAt(time)
                .versionNo(0L)
                .createdAt(time)
                .updatedAt(time)
                .build();
    }

    private static ImageGenerationExecutionInputArtifactEntity input(
            String executionId,
            String artifactId,
            String role,
            int order
    ) {
        return ImageGenerationExecutionInputArtifactEntity.builder()
                .imageGenerationExecutionId(executionId)
                .artifactId(artifactId)
                .inputRole(role)
                .inputOrder(order)
                .createdAt(Instant.parse("2026-08-31T12:00:00Z"))
                .build();
    }
}
