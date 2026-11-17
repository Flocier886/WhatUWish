package com.flocier.domain.ledger.service.replay.rich;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.event.ToolProgressPayload;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.infrastructure.persistent.memory.InMemoryRuntimeEventLedgerRepository;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.Map;

public class CodeInterpreterRuntimeReplayProjectorTest {
    @Test
    public void replaysBoundedStepWithoutUnknownFields() {
        var repository = new InMemoryRuntimeEventLedgerRepository();
        repository.save(RuntimeEventLedgerEntity.builder()
                .runtimeEventId("event-1").runId("run-1").sessionId("session-1")
                .requestId("request-1").toolInvocationId("invocation-1")
                .sequenceNo(1).eventType("code_interpreter_stage").stage("step")
                .status("running").message("代码分析步骤 1")
                .presentationKind("code_interpreter.step.v1").payloadSchemaVersion("1")
                .payloadJson(JSON.toJSONString(Map.of("step", 1, "code", "print(2)",
                        "observation", "2", "thought", "计算", "status", "running",
                        "storageKey", "private/key")))
                .occurredAt(Instant.parse("2026-11-04T10:00:00Z")).build());
        var run = RunLedgerEntity.builder().runId("run-1").sessionId("session-1")
                .requestId("request-1").agentId("agent-1").build();

        var projection = new CodeInterpreterRuntimeReplayProjector(repository).project(run);

        Assert.assertFalse(projection.isDataIncomplete());
        Assert.assertEquals(1, projection.getEvents().size());
        var payload = (ToolProgressPayload) projection.getEvents().get(0).getPayload();
        Assert.assertEquals("print(2)", payload.getPresentationData().get("code"));
        Assert.assertEquals("2", payload.getPresentationData().get("observation"));
        Assert.assertFalse(JSON.toJSONString(payload).contains("private/key"));
    }
}
