package com.flocier.test.trigger;

import com.flocier.application.agent.artifact.ArtifactContentApplicationService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.trigger.http.ArtifactContentController;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class ArtifactContentControllerTest {
    private ArtifactContentApplicationService service;
    private MockMvc mockMvc;

    @Before
    public void setUp() {
        service = mock(ArtifactContentApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ArtifactContentController(service)).build();
    }

    @Test
    public void shouldReturnControlledMetadataWithoutStorageFields() throws Exception {
        when(service.metadata("run-1", "artifact-1")).thenReturn(new ArtifactDeliveryFile(
                "artifact-1", "run-1", "report.md", "report", "text/markdown", 128L,
                "safe summary", "deep_search_tool", ArtifactAccessState.AVAILABLE,
                ArtifactPreviewDescriptor.markdownV1(), true, true));

        mockMvc.perform(get("/api/v1/artifacts/runs/run-1/artifact-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifactId").value("artifact-1"))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.preview.kind").value("markdown"))
                .andExpect(jsonPath("$.data.preview.schemaVersion").value("v1"))
                .andExpect(jsonPath("$.data.previewSupported").value(true))
                .andExpect(jsonPath("$.data.downloadSupported").value(true))
                .andExpect(content().string(not(containsString("storageKey"))))
                .andExpect(content().string(not(containsString("storageRef"))))
                .andExpect(content().string(not(containsString("private/"))));
    }

    @Test
    public void shouldReturnPlainMarkdownPreview() throws Exception {
        when(service.preview("run-1", "artifact-1")).thenReturn(
                ArtifactContentAccessService.ArtifactPreview.text(ArtifactPreviewDescriptor.markdownV1(), "# Preview"));

        MvcResult result = mockMvc.perform(get("/api/v1/artifacts/runs/run-1/artifact-1/preview"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string("# Preview"));
    }

    @Test
    public void shouldStreamBinaryImagePreviewWithExactMimeType() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        when(service.preview("run-1", "artifact-image")).thenReturn(
                ArtifactContentAccessService.ArtifactPreview.binary(ArtifactPreviewDescriptor.imageV1(), "image/png",
                        new ByteArrayInputStream(png)));

        MvcResult result = mockMvc.perform(get("/api/v1/artifacts/runs/run-1/artifact-image/preview"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/png"))
                .andExpect(content().bytes(png));
    }

    @Test
    public void shouldStreamOfficePreviewWithExactMimeType() throws Exception {
        byte[] office = new byte[]{'P', 'K', 3, 4, 1};
        String mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        when(service.preview("run-1", "artifact-docx")).thenReturn(
                ArtifactContentAccessService.ArtifactPreview.binary(ArtifactPreviewDescriptor.docxV1(), mime,
                        new ByteArrayInputStream(office)));

        MvcResult result = mockMvc.perform(get("/api/v1/artifacts/runs/run-1/artifact-docx/preview"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(mime))
                .andExpect(content().bytes(office));
    }

    @Test
    public void shouldStreamAttachmentWithSanitizedFileName() throws Exception {
        byte[] content = "download-body".getBytes(StandardCharsets.UTF_8);
        when(service.download("run-1", "artifact-1")).thenReturn(
                new ArtifactContentAccessService.ArtifactDownload("report\r\nInjected.md", "text/markdown",
                        new ByteArrayInputStream(content)));

        MvcResult result = mockMvc.perform(get("/api/v1/artifacts/runs/run-1/artifact-1/download"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", not(containsString("Injected.md\r\n"))))
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().bytes(content));
    }

    @Test
    public void shouldMapCrossRunDeletedMissingAndUnsupportedStatesWithoutDetails() throws Exception {
        when(service.metadata("run-other", "artifact-1"))
                .thenThrow(new ArtifactContentAccessException(ArtifactAccessState.NOT_FOUND));
        when(service.metadata("run-1", "deleted"))
                .thenThrow(new ArtifactContentAccessException(ArtifactAccessState.NOT_FOUND));
        when(service.metadata("run-1", "missing"))
                .thenReturn(new ArtifactDeliveryFile("missing", "run-1", "missing.md", "report",
                        "text/markdown", 10L, null, null, ArtifactAccessState.UNAVAILABLE, null, false, false));
        when(service.preview("run-1", "binary"))
                .thenThrow(new ArtifactContentAccessException(ArtifactAccessState.UNAVAILABLE));

        mockMvc.perform(get("/api/v1/artifacts/runs/run-other/artifact-1"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("run-1"))))
                .andExpect(content().string(not(containsString("storage"))));
        mockMvc.perform(get("/api/v1/artifacts/runs/run-1/deleted"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/artifacts/runs/run-1/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.previewSupported").value(false))
                .andExpect(jsonPath("$.data.downloadSupported").value(false));
        mockMvc.perform(get("/api/v1/artifacts/runs/run-1/binary/preview"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                .andExpect(content().string(not(containsString("private"))));
    }
}
