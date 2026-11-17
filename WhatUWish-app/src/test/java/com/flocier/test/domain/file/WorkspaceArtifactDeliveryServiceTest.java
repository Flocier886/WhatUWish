package com.flocier.test.domain.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeCollector;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationService;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationResult;
import com.flocier.domain.agent.runtime.artifact.WorkspaceArtifactDeliveryService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WorkspaceArtifactDeliveryServiceTest {
    @Test
    public void successfulRegistrationAddsArtifactIdToAgentObservation() {
        var collector = mock(ArtifactRuntimeCollector.class);
        var registration = mock(ArtifactRuntimeRegistrationService.class);
        var service = new WorkspaceArtifactDeliveryService(collector, registration,
                mock(IArtifactLedgerRepository.class), mock(ArtifactContentRemover.class));
        var context = AgentContext.builder().sessionId("session-1").build();
        context.setAttribute("runId", "run-1");
        var request = ToolInvokeRequest.builder().toolName("code_interpreter").toolCallId("call-1").build();
        var invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1")
                .runId("run-1").toolCallId("call-1").build();
        var result = ToolExecutionResult.builder().toolName("code_interpreter").success(true).status("success")
                .llmObservation("分析完成；报告位于 outputs/report.md")
                .artifactDescriptors(List.of(Map.of("artifactId", "artifact-1")))
                .artifactRefs(List.of()).build();
        when(collector.collect(request, result, context)).thenReturn(List.of(mock(ArtifactRuntimeDescriptor.class)));
        var artifact = ArtifactLedgerEntity.builder().artifactId("artifact-1").build();
        when(registration.registerBatchAndPublish(eq(context), eq(invocation), any(), anyList()))
                .thenReturn(List.of(new ArtifactRuntimeRegistrationResult(artifact, true)));

        service.register(context, request, result, invocation);

        assertTrue(result.isSuccess());
        assertEquals(List.of("artifact-1"), result.getArtifactRefs());
        assertTrue(result.getLlmObservation().contains("分析完成；报告位于 outputs/report.md"));
        assertTrue(result.getLlmObservation().contains("已登记产物 Artifact ID：artifact-1"));
    }

    @Test
    public void registrationFailureRevokesUnconfirmedArtifactId() {
        var collector = mock(ArtifactRuntimeCollector.class);
        var registration = mock(ArtifactRuntimeRegistrationService.class);
        var repository = mock(IArtifactLedgerRepository.class);
        var remover = mock(ArtifactContentRemover.class);
        var service = new WorkspaceArtifactDeliveryService(collector, registration, repository, remover);
        var context = AgentContext.builder().sessionId("session-1").build();
        context.setAttribute("runId", "run-1");
        var request = ToolInvokeRequest.builder().toolName("code_interpreter").toolCallId("call-1").build();
        var invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1")
                .runId("run-1").toolCallId("call-1").build();
        var output = Map.<String, Object>of("artifactId", "artifact-1", "storageType", "local_artifact_storage",
                "storageKey", "artifacts/artifact-1");
        var result = ToolExecutionResult.builder().toolName("code_interpreter").success(true).status("success")
                .llmObservation("分析完成").artifactDescriptors(List.of(output))
                .artifactRefs(List.of("artifact-1")).build();
        when(collector.collect(request, result, context)).thenReturn(List.of(mock(ArtifactRuntimeDescriptor.class)));
        when(registration.registerBatchAndPublish(eq(context), eq(invocation), any(), anyList()))
                .thenThrow(new IllegalStateException("ledger unavailable"));
        when(repository.findByRunIdAndToolCallId("run-1", "call-1")).thenReturn(List.of());
        when(repository.findByArtifactId("artifact-1")).thenReturn(Optional.empty());

        service.register(context, request, result, invocation);

        assertFalse(result.isSuccess());
        assertEquals("material_artifact_registration_failed", result.getErrorCode());
        assertTrue(result.getArtifactRefs().isEmpty());
        assertTrue(result.getArtifactDescriptors().isEmpty());
        assertFalse(result.getLlmObservation().contains("artifact-1"));
        verify(remover).remove(argThat(locator -> "artifacts/artifact-1".equals(locator.getStorageKey())));
    }
}
