package com.flocier.test.domain.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.DefaultToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.PreparedToolExecution;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolResponseEntity;
import com.flocier.domain.agent.runtime.agent.tool.rich.RichTool;
import com.flocier.domain.agent.runtime.agent.tool.rich.RichToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.rich.codeinterpreter.CodeInterpreterStreamClient;
import com.flocier.domain.agent.runtime.agent.tool.rich.codeinterpreter.CodeInterpreterTool;
import com.flocier.domain.agent.runtime.artifact.WorkspaceArtifactDeliveryService;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import com.flocier.domain.agent.runtime.file.WorkspaceOutputPersistence;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CodeInterpreterRichToolTest {
    @Test
    public void toolContractLeavesArtifactIdsToPostAnalysisDelivery() {
        var tool = tool(mock(CodeInterpreterStreamClient.class), mock(MaterialFileAccessResolver.class),
                mock(WorkspaceOutputPersistence.class), mock(WorkspaceArtifactDeliveryService.class),
                mock(LedgerFactService.class));

        assertTrue(tool.description().contains("本工具才登记输出并返回 Artifact ID"));
        assertTrue(String.valueOf(((Map<?, ?>) ((Map<?, ?>) tool.params()).get("properties"))
                .get("task")).contains("不要求内部 Agent 预先取得"));
    }

    @Test
    public void richRunnerDispatchesInterpreterThroughRichContract() {
        var defaultRunner = mock(DefaultToolRunner.class);
        var richRunner = new RichToolRunner(defaultRunner);
        var tool = mock(CodeInterpreterTool.class);
        when(tool.name()).thenReturn("code_interpreter");
        when(tool.richToolType()).thenReturn(CodeInterpreterTool.TOOL_TYPE);
        var request = ToolInvokeRequest.builder().toolName("code_interpreter").toolCallId("call-1").build();
        var context = AgentContext.builder().sessionId("session-1").build();
        var invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1").build();
        var startedAt = Instant.now();
        var prepared = PreparedToolExecution.ready(request, tool, invocation, startedAt);
        var result = ToolExecutionResult.builder().toolName("code_interpreter").success(true).build();
        when(defaultRunner.prepareResolved(eq(tool), eq(request), eq(context),
                eq(CodeInterpreterTool.TOOL_TYPE), any(Instant.class))).thenReturn(prepared);
        when(tool.executeRich(request, context, invocation, startedAt)).thenReturn(result);

        assertSame(result, richRunner.run(new ToolCollection(List.of(tool)), request, context));
        verify(defaultRunner, never()).execute(any(), any());
        verify(tool, never()).execute(any(), any());
    }

    @Test
    public void completesOneRichInvocationAfterConfirmedTerminal() throws Exception {
        var access = mock(MaterialFileAccessResolver.class);
        var stream = mock(CodeInterpreterStreamClient.class);
        var outputs = mock(WorkspaceOutputPersistence.class);
        var delivery = mock(WorkspaceArtifactDeliveryService.class);
        var ledger = mock(LedgerFactService.class);
        var tool = tool(stream, access, outputs, delivery, ledger);
        var context = AgentContext.builder().sessionId("session-1").build();
        var request = ToolInvokeRequest.builder().toolName("code_interpreter").toolCallId("call-1")
                .arguments(Map.of("task", "分析数据")).build();
        var invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1").build();
        var prepared = new MaterialFileAccessResolver.Prepared(Map.of("task", "分析数据"), List.of(), "C:/workspace");
        when(access.prepare(eq(context), eq("code_interpreter"), anyMap())).thenReturn(prepared);
        var response = new PythonToolResponseEntity();
        response.setSuccess(true);
        response.setOutput("完成分析");
        response.setLlmObservation("代码分析完成：完成分析");
        when(stream.stream(anyString(), eq(request), eq(context), eq(prepared), any())).thenReturn(response);
        when(outputs.persist(anyList(), eq("C:/workspace"), eq("code_interpreter"))).thenReturn(List.of());

        ToolExecutionResult result = tool.executeRich(request, context, invocation, Instant.now());

        assertTrue(tool instanceof RichTool);
        assertEquals(CodeInterpreterTool.TOOL_TYPE, result.getSourceType());
        assertTrue(result.isSuccess());
        assertTrue(result.getArtifactRefs().isEmpty());
        verify(delivery).register(context, request, result, invocation);
        verify(ledger, times(1)).finishToolInvocation(invocation, result);
    }

    @Test
    public void upstreamFailureSkipsFileDeliveryAndFinishesInvocation() throws Exception {
        var access = mock(MaterialFileAccessResolver.class);
        var stream = mock(CodeInterpreterStreamClient.class);
        var outputs = mock(WorkspaceOutputPersistence.class);
        var delivery = mock(WorkspaceArtifactDeliveryService.class);
        var ledger = mock(LedgerFactService.class);
        var tool = tool(stream, access, outputs, delivery, ledger);
        var context = AgentContext.builder().sessionId("session-1").build();
        var request = ToolInvokeRequest.builder().toolName("code_interpreter").toolCallId("call-1")
                .arguments(Map.of("task", "分析数据")).build();
        var invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1").build();
        var prepared = new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), "C:/workspace");
        when(access.prepare(eq(context), eq("code_interpreter"), anyMap())).thenReturn(prepared);
        var response = new PythonToolResponseEntity();
        response.setSuccess(false);
        response.setErrorCode("code_interpreter_stream_terminated");
        response.setErrorMessage("代码分析流缺少终态");
        when(stream.stream(anyString(), eq(request), eq(context), eq(prepared), any())).thenReturn(response);

        ToolExecutionResult result = tool.executeRich(request, context, invocation, Instant.now());

        assertFalse(result.isSuccess());
        assertEquals("code_interpreter_stream_terminated", result.getErrorCode());
        assertTrue(result.getArtifactRefs().isEmpty());
        verifyNoInteractions(outputs, delivery);
        verify(ledger, times(1)).finishToolInvocation(invocation, result);
    }

    @Test
    public void interruptionAfterContentWriteDiscardsUndeliveredFiles() throws Exception {
        var access = mock(MaterialFileAccessResolver.class);
        var stream = mock(CodeInterpreterStreamClient.class);
        var outputs = mock(WorkspaceOutputPersistence.class);
        var delivery = mock(WorkspaceArtifactDeliveryService.class);
        var ledger = mock(LedgerFactService.class);
        var tool = tool(stream, access, outputs, delivery, ledger);
        var context = AgentContext.builder().sessionId("session-1").build();
        var request = ToolInvokeRequest.builder().toolName("code_interpreter").toolCallId("call-1")
                .arguments(Map.of("task", "分析数据")).build();
        var invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1").build();
        var prepared = new MaterialFileAccessResolver.Prepared(Map.of(), List.of(), "C:/workspace");
        var descriptors = List.<Map<String, Object>>of(Map.of("artifactId", "artifact-1", "storageKey", "key-1"));
        when(access.prepare(eq(context), eq("code_interpreter"), anyMap())).thenReturn(prepared);
        var response = new PythonToolResponseEntity();
        response.setSuccess(true);
        when(stream.stream(anyString(), eq(request), eq(context), eq(prepared), any())).thenReturn(response);
        when(outputs.persist(anyList(), eq("C:/workspace"), eq("code_interpreter"))).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            return descriptors;
        });
        try {
            ToolExecutionResult result = tool.executeRich(request, context, invocation, Instant.now());
            assertFalse(result.isSuccess());
            assertEquals("code_interpreter_interrupted", result.getErrorCode());
            assertTrue(result.getArtifactRefs().isEmpty());
            verify(outputs).discard(descriptors);
            verifyNoInteractions(delivery);
        } finally {
            Thread.interrupted();
        }
    }

    private CodeInterpreterTool tool(CodeInterpreterStreamClient stream, MaterialFileAccessResolver access,
                                     WorkspaceOutputPersistence outputs, WorkspaceArtifactDeliveryService delivery,
                                     LedgerFactService ledger) {
        return new CodeInterpreterTool("http://unused", stream, access, outputs, delivery, ledger,
                mock(RichRuntimeProgressRecordingService.class));
    }
}
