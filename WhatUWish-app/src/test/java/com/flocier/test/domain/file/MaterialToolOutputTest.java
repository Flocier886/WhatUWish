package com.flocier.test.domain.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.artifact.WorkspaceArtifactDeliveryService;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeCollector;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationService;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.MaterialTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.CodeExecutionTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.BashTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolClient;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolResponseEntity;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 共享工作区产物由 Java 保存，写入失败时不得向 Agent 宣称 Artifact 已交付。 */
public class MaterialToolOutputTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test
    public void materialBridgeUsesDedicatedTimeoutAndFailsClosedOnTimeout() throws Exception {
        PythonToolClient client = new PythonToolClient();
        HttpClient transport = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", transport);
        ReflectionTestUtils.setField(client, "materialBridgeToken", "test-secret");
        ReflectionTestUtils.setField(client, "materialTimeoutSeconds", 3);
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timed out"));
        var response = client.executeMaterial("http://unused.invalid", "csv_processor",
                ToolInvokeRequest.builder().toolName("csv_processor").arguments(Map.of()).build(),
                AgentContext.builder().build(), new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), "C:/shared/run"));

        assertFalse(response.isSuccess());
        assertEquals("material_transport_error", response.getErrorCode());
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(Duration.ofSeconds(3), request.getValue().timeout().orElseThrow());
        assertEquals("application/json", request.getValue().headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    public void docgenBridgeMatchesDocumentRenderingBudget() throws Exception {
        PythonToolClient client = new PythonToolClient();
        HttpClient transport = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", transport);
        ReflectionTestUtils.setField(client, "materialBridgeToken", "test-secret");
        ReflectionTestUtils.setField(client, "materialTimeoutSeconds", 180);
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timed out"));
        client.executeMaterial("http://unused.invalid", "document_generate",
                ToolInvokeRequest.builder().toolName("document_generate").arguments(Map.of()).build(),
                AgentContext.builder().build(), new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), "C:/shared/run"));
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(Duration.ofSeconds(240), request.getValue().timeout().orElseThrow());
    }

    @Test
    public void bashBridgeAllowsMasterCommandTimeout() throws Exception {
        PythonToolClient client = new PythonToolClient();
        HttpClient transport = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", transport);
        ReflectionTestUtils.setField(client, "materialBridgeToken", "test-secret");
        ReflectionTestUtils.setField(client, "materialTimeoutSeconds", 180);
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timed out"));
        client.executeMaterial("http://unused.invalid", "bash",
                ToolInvokeRequest.builder().toolName("bash")
                        .arguments(Map.of("command", "sleep 1", "timeout_sec", 600)).build(),
                AgentContext.builder().build(),
                new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), "C:/shared/run"));
        var captured = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(captured.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(Duration.ofSeconds(660), captured.getValue().timeout().orElseThrow());
    }

    @Test
    public void bashRegistersOutputAndPreservesCommandFailureDetails() throws Exception {
        byte[] content = "from bash".getBytes(StandardCharsets.UTF_8);
        Path root = workspaceWithOutput(content);
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), eq("bash"), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of("command", "exit 7"), List.of(), root.toString()));
        PythonToolResponseEntity response = response();
        response.setStatus("failed");
        response.setLlmObservation("bash 退出码：7。\nstderr:\ncommand failed");
        response.setStructured(Map.of("exitCode", 7, "stderr", "command failed"));
        response.setMaterialOutputs(List.of(Map.of("artifactId", "sample-id", "fileName", "data.csv",
                "relativePath", "outputs/data.csv", "sha256", sha256(content))));
        PythonToolClient client = mock(PythonToolClient.class);
        when(client.executeMaterial(anyString(), eq("bash"), any(), any(), any())).thenReturn(response);
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        when(writer.write(any())).thenReturn(new ArtifactStoredContent(content.length));
        BashTool tool = new BashTool("http://python", client, access, writer,
                mock(ArtifactContentRemover.class), "local_artifact_storage", "", null, null);
        ToolResultPayload result = (ToolResultPayload) tool.execute(
                ToolInvokeRequest.builder().toolName("bash").arguments(Map.of("command", "exit 7")).build(),
                AgentContext.builder().build());
        assertTrue(result.isSuccess());
        assertEquals("failed", result.getStatus());
        assertTrue(result.getLlmObservation().contains("command failed"));
        assertTrue(result.getLlmObservation().contains("勿再调用 workspace_register"));
        assertEquals("sample-id", result.getArtifactDescriptors().get(0).get("artifactId"));
        verify(writer).write(any());
    }

    @Test
    public void bashSendsOnlyRegisteredSkillPathsThroughMaterialBridge() throws Exception {
        Path workspace = workspaceWithOutput("data".getBytes(StandardCharsets.UTF_8));
        Path library = temporary.newFolder("skill-library").toPath();
        Path registered = Files.createDirectories(library.resolve("demo"));
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), eq("bash"), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of("command", "python skills/demo/scripts/run.py"),
                        List.of(), workspace.toString()));
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.listSkills()).thenReturn(List.of(SkillDefinition.builder()
                .name("demo").basePath(registered).enabled(true).build()));
        SkillRuntimeOptions options = SkillRuntimeOptions.builder()
                .enabled(true).directories(List.of(library.toString())).build();
        PythonToolClient client = mock(PythonToolClient.class);
        PythonToolResponseEntity response = new PythonToolResponseEntity();
        response.setSuccess(true);
        when(client.executeMaterial(anyString(), eq("bash"), any(), any(), any())).thenReturn(response);
        BashTool tool = new BashTool("http://python", client, access,
                mock(ArtifactContentWriter.class), mock(ArtifactContentRemover.class),
                "local_artifact_storage", "", registry, options);
        tool.execute(ToolInvokeRequest.builder().toolName("bash")
                .arguments(Map.of("command", "python skills/demo/scripts/run.py")).build(),
                AgentContext.builder().build());
        var prepared = org.mockito.ArgumentCaptor.forClass(MaterialFileAccessResolver.Prepared.class);
        verify(client).executeMaterial(anyString(), eq("bash"), any(), any(), prepared.capture());
        assertEquals(Map.of("demo", registered.toString()), prepared.getValue().arguments().get("__skillPaths"));
        assertEquals(library.toString(), prepared.getValue().arguments().get("__skillLibraryRoot"));
    }

    @Test
    public void materialBridgePreservesInterruptedStatus() throws Exception {
        PythonToolClient client = new PythonToolClient();
        HttpClient transport = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", transport);
        ReflectionTestUtils.setField(client, "materialBridgeToken", "test-secret");
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("stopped"));
        try {
            var response = client.executeMaterial("http://unused.invalid", "csv_processor",
                    ToolInvokeRequest.builder().toolName("csv_processor").arguments(Map.of()).build(),
                    AgentContext.builder().build(), new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), "C:/shared/run"));
            assertFalse(response.isSuccess());
            assertEquals("material_interrupted", response.getErrorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void storesSharedWorkspaceFileOnJavaSideAndPreservesArtifactId() throws Exception {
        byte[] content = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        Path root = workspaceWithOutput(content);
        PythonToolResponseEntity response = response();
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), anyString(), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), root.toString()));
        PythonToolClient client = mock(PythonToolClient.class);
        when(client.executeMaterial(anyString(), anyString(), any(), any(), any())).thenReturn(response);
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        when(writer.write(any())).thenReturn(new ArtifactStoredContent(content.length));
        ArtifactContentRemover remover = mock(ArtifactContentRemover.class);
        ToolResultPayload result = (ToolResultPayload) tool(client, access, writer, remover).execute(
                ToolInvokeRequest.builder().toolName("data_clean").arguments(Map.of()).build(),
                AgentContext.builder().build());

        assertTrue(result.isSuccess());
        assertEquals("sample-id", result.getArtifactRefs().get(0));
        assertEquals("local_artifact_storage", result.getArtifactDescriptors().get(0).get("storageType"));
        verify(writer).write(argThat(write -> java.util.Arrays.equals(content, write.getContent())));
    }

    @Test
    public void codeExecutionKeepsFailureObservationAndPartialFileCandidate() throws Exception {
        Path root = workspaceWithOutput("partial".getBytes(StandardCharsets.UTF_8));
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), anyString(), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of("source", "raise RuntimeError('boom')"),
                        List.of(), root.toString()));
        PythonToolResponseEntity response = response();
        response.setSuccess(false);
        response.setStatus("failed");
        response.setErrorCode("code_runtime_error");
        response.setErrorMessage("boom");
        response.setLlmObservation("stdout: before failure\nstderr: boom");
        response.setMaterialOutputs(List.of(Map.of("artifactId", "sample-id", "fileName", "data.csv",
                "relativePath", "outputs/data.csv", "sha256", sha256("partial".getBytes(StandardCharsets.UTF_8)))));
        PythonToolClient client = mock(PythonToolClient.class);
        when(client.executeMaterial(anyString(), eq("code_execution"), any(), any(), any()))
                .thenReturn(response);
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        when(writer.write(any())).thenReturn(new ArtifactStoredContent(7));
        CodeExecutionTool tool = new CodeExecutionTool("http://python", client, access, writer,
                mock(ArtifactContentRemover.class), "local_artifact_storage", "");

        ToolResultPayload result = (ToolResultPayload) tool.execute(
                ToolInvokeRequest.builder().toolName("code_execution").arguments(Map.of("source", "x"))
                        .build(), AgentContext.builder().build());

        assertFalse(result.isSuccess());
        assertEquals("code_runtime_error", result.getErrorCode());
        assertTrue(result.getLlmObservation().contains("before failure"));
        assertEquals("sample-id", result.getArtifactDescriptors().get(0).get("artifactId"));
        verify(writer).write(any());
    }

    @Test
    public void codeExecutionRejectsOutputChangedAfterPythonSnapshot() throws Exception {
        Path root = workspaceWithOutput("changed".getBytes(StandardCharsets.UTF_8));
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), anyString(), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), root.toString()));
        PythonToolClient client = mock(PythonToolClient.class);
        PythonToolResponseEntity response = response();
        response.setMaterialOutputs(List.of(Map.of("artifactId", "sample-id", "fileName", "data.csv",
                "relativePath", "outputs/data.csv", "sha256", sha256("original".getBytes(StandardCharsets.UTF_8)))));
        when(client.executeMaterial(anyString(), eq("code_execution"), any(), any(), any())).thenReturn(response);
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        CodeExecutionTool tool = new CodeExecutionTool("http://python", client, access, writer,
                mock(ArtifactContentRemover.class), "local_artifact_storage", "");

        ToolResultPayload result = (ToolResultPayload) tool.execute(
                ToolInvokeRequest.builder().toolName("code_execution").arguments(Map.of("source", "print(1)"))
                        .build(), AgentContext.builder().build());
        assertFalse(result.isSuccess());
        assertTrue(result.getArtifactRefs().isEmpty());
        verify(writer, never()).write(any());
    }

    @Test
    public void codeExecutionDoesNotPersistAfterInterruptedHttpCall() throws Exception {
        Path root = workspaceWithOutput("data".getBytes(StandardCharsets.UTF_8));
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), anyString(), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), root.toString()));
        PythonToolClient client = mock(PythonToolClient.class);
        when(client.executeMaterial(anyString(), eq("code_execution"), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return response();
                });
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        CodeExecutionTool tool = new CodeExecutionTool("http://python", client, access, writer,
                mock(ArtifactContentRemover.class), "local_artifact_storage", "");
        try {
            ToolResultPayload result = (ToolResultPayload) tool.execute(
                    ToolInvokeRequest.builder().toolName("code_execution").arguments(Map.of("source", "print(1)"))
                            .build(), AgentContext.builder().build());
            assertFalse(result.isSuccess());
            assertEquals("material_interrupted", result.getErrorCode());
            assertTrue(result.getArtifactRefs().isEmpty());
            verify(writer, never()).write(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void failedSecondOutputCompensatesFirstObject() throws Exception {
        Path root = workspaceWithOutput("first".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("outputs/second.csv"), "second".getBytes(StandardCharsets.UTF_8));
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), anyString(), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), root.toString()));
        PythonToolClient client = mock(PythonToolClient.class);
        PythonToolResponseEntity response = response();
        response.setMaterialOutputs(List.of(
                Map.of("artifactId", "first-id", "fileName", "data.csv", "relativePath", "outputs/data.csv"),
                Map.of("artifactId", "second-id", "fileName", "second.csv", "relativePath", "outputs/second.csv")));
        when(client.executeMaterial(anyString(), anyString(), any(), any(), any())).thenReturn(response);
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        when(writer.write(any())).thenReturn(new ArtifactStoredContent(5))
                .thenThrow(new IllegalStateException("second write failed"));
        ArtifactContentRemover remover = mock(ArtifactContentRemover.class);

        ToolResultPayload result = (ToolResultPayload) tool(client, access, writer, remover).execute(
                ToolInvokeRequest.builder().toolName("data_clean").arguments(Map.of()).build(),
                AgentContext.builder().build());
        assertFalse(result.isSuccess());
        assertTrue(result.getArtifactRefs().isEmpty());
        verify(remover, times(1)).remove(argThat(locator ->
                locator.getStorageKey().contains("first-id")));
    }

    @Test
    public void failsClosedWhenJavaStoreCannotPersistOutput() throws Exception {
        Path root = workspaceWithOutput("data".getBytes(StandardCharsets.UTF_8));
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), anyString(), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), root.toString()));
        PythonToolClient client = mock(PythonToolClient.class);
        when(client.executeMaterial(anyString(), anyString(), any(), any(), any()))
                .thenReturn(response());
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        when(writer.write(any())).thenThrow(new IllegalStateException("store unavailable"));
        ArtifactContentRemover remover = mock(ArtifactContentRemover.class);
        ToolResultPayload result = (ToolResultPayload) tool(client, access, writer, remover).execute(
                ToolInvokeRequest.builder().toolName("data_clean").arguments(Map.of()).build(),
                AgentContext.builder().build());

        assertFalse(result.isSuccess());
        assertTrue(result.getArtifactRefs().isEmpty());
        assertEquals("material_output_failed", result.getErrorCode());
        verify(remover, never()).remove(any());
    }

    @Test
    public void refusesOutputPathOutsideSharedRunDirectory() throws Exception {
        Path root = workspaceWithOutput("data".getBytes(StandardCharsets.UTF_8));
        MaterialFileAccessResolver access = mock(MaterialFileAccessResolver.class);
        when(access.prepare(any(), anyString(), anyMap())).thenReturn(
                new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), root.toString()));
        PythonToolResponseEntity invalid = response();
        invalid.setMaterialOutputs(List.of(Map.of("artifactId", "sample-id", "fileName", "data.csv",
                "relativePath", "outputs/../../private/data.csv")));
        PythonToolClient client = mock(PythonToolClient.class);
        when(client.executeMaterial(anyString(), anyString(), any(), any(), any())).thenReturn(invalid);
        ArtifactContentWriter writer = mock(ArtifactContentWriter.class);
        ToolResultPayload result = (ToolResultPayload) tool(client, access, writer,
                mock(ArtifactContentRemover.class)).execute(
                ToolInvokeRequest.builder().toolName("data_clean").arguments(Map.of()).build(),
                AgentContext.builder().build());
        assertFalse(result.isSuccess());
        verify(writer, never()).write(any());
    }

    @Test
    public void compensationOnlyRemovesObjectsWithoutLedgerIdentity() {
        ArtifactContentRemover remover = mock(ArtifactContentRemover.class);
        IArtifactLedgerRepository repository = mock(IArtifactLedgerRepository.class);
        WorkspaceArtifactDeliveryService delivery = delivery(remover, repository);
        when(repository.findByArtifactId("existing-id")).thenReturn(
                java.util.Optional.of(ArtifactLedgerEntity.builder().artifactId("existing-id").build()));
        when(repository.findByArtifactId("new-id")).thenReturn(java.util.Optional.empty());

        AgentContext context = AgentContext.builder().build();
        context.setAttribute("runId", "run-1");
        ReflectionTestUtils.invokeMethod(delivery, "cleanupUnregistered", List.of(
                Map.of("artifactId", "existing-id", "storageType", "s3_object_storage",
                        "storageKey", "v1/artifacts/existing-id/content"),
                Map.of("artifactId", "new-id", "storageType", "s3_object_storage",
                        "storageKey", "v1/artifacts/new-id/content")), context,
                ToolInvokeRequest.builder().toolCallId("call-1").build());

        verify(remover, times(1)).remove(argThat(locator ->
                "v1/artifacts/new-id/content".equals(locator.getStorageKey())));
    }

    @Test
    public void compensationPreservesObjectReferencedByAnotherLedgerId() {
        ArtifactContentRemover remover = mock(ArtifactContentRemover.class);
        IArtifactLedgerRepository repository = mock(IArtifactLedgerRepository.class);
        WorkspaceArtifactDeliveryService delivery = delivery(remover, repository);
        when(repository.findByRunIdAndToolCallId("run-1", "call-1")).thenReturn(List.of(
                ArtifactLedgerEntity.builder().artifactId("ledger-id")
                        .storageType("s3_object_storage")
                        .storageKey("v1/artifacts/new-id/content").build()));
        AgentContext context = AgentContext.builder().build();
        context.setAttribute("runId", "run-1");

        ReflectionTestUtils.invokeMethod(delivery, "cleanupUnregistered", List.of(
                Map.of("artifactId", "new-id", "storageType", "s3_object_storage",
                        "storageKey", "v1/artifacts/new-id/content")), context,
                ToolInvokeRequest.builder().toolCallId("call-1").build());

        verify(remover, never()).remove(any());
    }

    private WorkspaceArtifactDeliveryService delivery(ArtifactContentRemover remover,
                                                       IArtifactLedgerRepository repository) {
        return new WorkspaceArtifactDeliveryService(mock(ArtifactRuntimeCollector.class),
                mock(ArtifactRuntimeRegistrationService.class), repository, remover);
    }

    private Path workspaceWithOutput(byte[] content) throws Exception {
        Path root = temporary.newFolder("workspace").toPath();
        Files.createDirectory(root.resolve("outputs"));
        Files.write(root.resolve("outputs/data.csv"), content);
        return root;
    }

    private PythonToolResponseEntity response() {
        PythonToolResponseEntity response = new PythonToolResponseEntity();
        response.setSuccess(true);
        response.setOutput("done");
        response.setLlmObservation("done");
        response.setMaterialOutputs(List.of(Map.of("artifactId", "sample-id", "fileName", "data.csv",
                "relativePath", "outputs/data.csv")));
        return response;
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private MaterialTool tool(PythonToolClient client, MaterialFileAccessResolver access,
                              ArtifactContentWriter writer, ArtifactContentRemover remover) {
        return new MaterialTool("data_clean", "clean", Map.of(), "http://python", client, access,
                writer, remover, "local_artifact_storage", "");
    }
}
