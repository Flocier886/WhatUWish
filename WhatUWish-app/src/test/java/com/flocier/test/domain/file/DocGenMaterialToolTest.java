package com.flocier.test.domain.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.docgen.DocGenMaterialTool;
import com.flocier.domain.agent.runtime.agent.tool.docgen.DocumentGenerateTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolClient;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolResponseEntity;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DocGenMaterialToolTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void savesPdfWithExplicitPreviewAndNormalizedOutput() throws Exception {
        Path root = temporary.newFolder("workspace").toPath();
        Files.createDirectories(root.resolve("outputs"));
        byte[] pdf = "%PDF-1.7\n1 0 obj\n".getBytes();
        Files.write(root.resolve("outputs/report.pdf"), pdf);
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), eq("document_generate"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> args = invocation.getArgument(2);
            assertEquals("outputs/report.pdf", args.get("output_path"));
            return new MaterialFileAccessResolver.Prepared(args, List.of(), root.toString());
        });
        PythonToolResponseEntity response = new PythonToolResponseEntity();
        response.setSuccess(true);
        response.setLlmObservation("PDF 已生成");
        response.setMaterialOutputs(List.of(Map.of("artifactId", "docgen-pdf-1",
                "fileName", "report.pdf", "relativePath", "outputs/report.pdf")));
        PythonToolClient client = mock(PythonToolClient.class);
        when(client.executeMaterial(anyString(), eq("document_generate"), any(), any(), any())).thenReturn(response);
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        when(writer.write(any())).thenReturn(new ArtifactStoredContent(pdf.length));
        var tool = new DocGenMaterialTool(new DocumentGenerateTool(), "http://python", client, access,
                writer, mock(ArtifactContentRemover.class), "local_artifact_storage", "");
        ToolResultPayload result = (ToolResultPayload) tool.execute(ToolInvokeRequest.builder()
                .toolName("document_generate").arguments(Map.of("output_path", "report.pdf", "content", "# 报告"))
                .build(), AgentContext.builder().build());
        assertTrue(result.isSuccess());
        assertEquals("docgen-pdf-1", result.getArtifactDescriptors().get(0).get("artifactId"));
        assertEquals(Map.of("preview", Map.of("kind", "pdf", "schemaVersion", "v1")),
                result.getArtifactDescriptors().get(0).get("metadata"));
        verify(writer).write(any());
    }

    @Test
    public void rejectsPathTraversalBeforeCallingPython() {
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        PythonToolClient client = mock(PythonToolClient.class);
        var tool = new DocGenMaterialTool(new DocumentGenerateTool(), "http://python", client, access,
                mock(ArtifactContentWriter.class), mock(ArtifactContentRemover.class),
                "local_artifact_storage", "");
        ToolResultPayload result = (ToolResultPayload) tool.execute(ToolInvokeRequest.builder()
                .toolName("document_generate").arguments(Map.of("output_path", "../outside.pdf", "content", "x"))
                .build(), AgentContext.builder().build());
        assertFalse(result.isSuccess());
        verifyNoInteractions(access, client);
    }
}
