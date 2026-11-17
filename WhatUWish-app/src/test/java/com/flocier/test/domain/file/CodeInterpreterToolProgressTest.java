package com.flocier.test.domain.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.rich.codeinterpreter.CodeInterpreterTool;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressCommand;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class CodeInterpreterToolProgressTest {
    @Test
    public void recordsStepWithStringAgentIdFromGenericContext() {
        var progress = mock(RichRuntimeProgressRecordingService.class);
        var tool = new CodeInterpreterTool("http://unused", null, null, null, null, null, progress);
        var invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1")
                .runId("run-1").sessionId("session-1").requestId("request-1").build();
        var context = AgentContext.builder().sessionId("session-1").build();
        context.setAttribute("agentId", "react-runtime");

        ReflectionTestUtils.invokeMethod(tool, "recordStep", invocation, context,
                Map.of("type", "step", "sequence", 1, "step", 1,
                        "thought", "检查", "code", "print(2)", "observation", "2"));

        var captured = ArgumentCaptor.forClass(RichRuntimeProgressCommand.class);
        verify(progress).record(captured.capture());
        Assert.assertEquals("react-runtime", captured.getValue().getAgentId());
        Assert.assertEquals("print(2)", captured.getValue().getPresentationData().get("code"));
    }

    @Test
    public void upstreamSuccessStaysRunningUntilArtifactsAreRegistered() {
        var progress = mock(RichRuntimeProgressRecordingService.class);
        var tool = new CodeInterpreterTool("http://unused", null, null, null, null, null, progress);
        var invocation = ToolInvocationLedgerEntity.builder().toolInvocationId("invocation-1")
                .runId("run-1").sessionId("session-1").requestId("request-1").build();
        var context = AgentContext.builder().sessionId("session-1").build();
        context.setAttribute("agentId", "react-runtime");

        ReflectionTestUtils.invokeMethod(tool, "recordStep", invocation, context,
                Map.of("type", "result", "sequence", 2, "status", "success", "stepCount", 1,
                        "conclusion", "分析完成"));

        var captured = ArgumentCaptor.forClass(RichRuntimeProgressCommand.class);
        verify(progress).record(captured.capture());
        Assert.assertEquals("analysis_finished", captured.getValue().getStage());
        Assert.assertEquals("running", captured.getValue().getStatus());
    }
}
