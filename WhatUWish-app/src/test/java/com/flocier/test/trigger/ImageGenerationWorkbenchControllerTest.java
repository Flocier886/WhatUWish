package com.flocier.test.trigger;

import com.flocier.application.agent.artifact.ArtifactContentApplicationService;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageArtifact;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationException;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationExecutionService;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationHistoryItem;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationHistoryPage;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationHistoryService;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationResult;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.trigger.http.ImageGenerationWorkbenchController;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 工作台 HTTP 合同只暴露 execution 和受控 Artifact，不回落到 Runtime API。 */
public class ImageGenerationWorkbenchControllerTest {
    private WorkbenchImageGenerationExecutionService executionService;
    private WorkbenchImageGenerationHistoryService historyService;
    private ArtifactContentApplicationService artifactContentService;
    private MockMvc mockMvc;

    @Before
    public void setUp() {
        executionService = mock(WorkbenchImageGenerationExecutionService.class);
        historyService = mock(WorkbenchImageGenerationHistoryService.class);
        artifactContentService = mock(ArtifactContentApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ImageGenerationWorkbenchController(
                executionService, historyService, artifactContentService)).build();
    }

    @Test
    public void shouldSubmitDirectMultipartGenerationWithoutRuntimeIdentifiers() throws Exception {
        when(executionService.execute(any())).thenReturn(WorkbenchImageGenerationResult.builder()
                .executionId("workbench-execution-1")
                .status("completed")
                .summary("图片生成完成")
                .requestedCount(1)
                .generatedCount(1)
                .artifacts(List.of(artifact()))
                .build());

        mockMvc.perform(multipart("/api/v1/image-generation-workbench/generations")
                        .file(new MockMultipartFile("references", "source.png", "image/png", new byte[] {1, 2, 3}))
                        .param("sessionId", "workspace-session")
                        .param("prompt", "生成海报")
                        .param("mode", "edits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.executionId").value("workbench-execution-1"))
                .andExpect(jsonPath("$.data.artifacts[0].artifactId").value("artifact-1"))
                .andExpect(content().string(not(containsString("runId"))))
                .andExpect(content().string(not(containsString("storageKey"))))
                .andExpect(content().string(not(containsString("storageRef"))));
    }

    @Test
    public void shouldReturnControlledRequestValidationFailure() throws Exception {
        when(executionService.execute(any())).thenThrow(new WorkbenchImageGenerationException(
                "image_generation_request_invalid", "图片工作台缺少会话或生成描述。"));

        mockMvc.perform(multipart("/api/v1/image-generation-workbench/generations")
                        .param("sessionId", "workspace-session")
                        .param("prompt", "生成海报"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("image_generation_request_invalid"));
    }

    @Test
    public void shouldReturnWorkbenchHistoryAndControlledImageMetadata() throws Exception {
        when(historyService.query("workspace-session", 1, 12)).thenReturn(WorkbenchImageGenerationHistoryPage.builder()
                .sessionId("workspace-session")
                .pageNo(1)
                .pageSize(12)
                .total(1)
                .hasMore(false)
                .items(List.of(WorkbenchImageGenerationHistoryItem.builder()
                        .executionId("workbench-execution-1")
                        .status("completed")
                        .startedAt(Instant.parse("2026-08-31T08:00:00Z"))
                        .artifacts(List.of(artifact()))
                        .build()))
                .build());
        when(artifactContentService.metadata("workbench-execution-1", "artifact-1")).thenReturn(new ArtifactDeliveryFile(
                "artifact-1", "workbench-execution-1", "output.png", "image", "image/png", 128L,
                "图片", "image_generation_workbench", ArtifactAccessState.AVAILABLE,
                ArtifactPreviewDescriptor.imageV1(), true, true));

        mockMvc.perform(get("/api/v1/image-generation-workbench/history")
                        .param("sessionId", "workspace-session")
                        .param("pageNo", "1")
                        .param("pageSize", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].executionId").value("workbench-execution-1"))
                .andExpect(content().string(not(containsString("agentId"))));
        mockMvc.perform(get("/api/v1/image-generation-workbench/executions/workbench-execution-1/artifacts/artifact-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifactId").value("artifact-1"))
                .andExpect(content().string(not(containsString("runId"))))
                .andExpect(content().string(not(containsString("storageKey"))))
                .andExpect(content().string(not(containsString("storageRef"))));
    }

    @Test
    public void shouldStreamPreviewAndKeepArtifactFailureControlled() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        when(artifactContentService.preview("workbench-execution-1", "artifact-1")).thenReturn(
                ArtifactContentAccessService.ArtifactPreview.binary(ArtifactPreviewDescriptor.imageV1(), "image/png",
                        new ByteArrayInputStream(png)));
        when(artifactContentService.download("workbench-execution-1", "artifact-1")).thenReturn(
                new ArtifactContentAccessService.ArtifactDownload("output.png", "image/png", new ByteArrayInputStream(png)));
        when(artifactContentService.metadata("workbench-execution-1", "deleted"))
                .thenThrow(new ArtifactContentAccessException(ArtifactAccessState.NOT_FOUND));

        MvcResult result = mockMvc.perform(get("/api/v1/image-generation-workbench/executions/workbench-execution-1/artifacts/artifact-1/preview"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/png"))
                .andExpect(content().bytes(png));
        MvcResult downloadResult = mockMvc.perform(get("/api/v1/image-generation-workbench/executions/workbench-execution-1/artifacts/artifact-1/download"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(downloadResult))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("image/png"))
                .andExpect(content().bytes(png));
        mockMvc.perform(get("/api/v1/image-generation-workbench/executions/workbench-execution-1/artifacts/deleted"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("storage"))));
    }

    private WorkbenchImageArtifact artifact() {
        return WorkbenchImageArtifact.builder()
                .artifactId("artifact-1")
                .name("output.png")
                .mimeType("image/png")
                .size(128L)
                .accessState("AVAILABLE")
                .preview(ArtifactPreviewDescriptor.imageV1())
                .previewSupported(true)
                .downloadSupported(true)
                .build();
    }
}
