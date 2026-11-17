package com.flocier.test.domain.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.event.InputFilePayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import com.flocier.domain.agent.runtime.file.ConversationRunInputRecorder;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ConversationRunInputRecorderTest {
    @Test
    public void recordsOrderedInputsOncePerRunAndPublishesSameIdentity() {
        LedgerRecordingService ledger = mock(LedgerRecordingService.class);
        var stored = new HashMap<String, ArtifactLedgerEntity>();
        when(ledger.recordArtifact(any())).thenAnswer(call -> {
            ArtifactLedgerEntity candidate = call.getArgument(0);
            return stored.computeIfAbsent(candidate.getIdempotencyKey(), ignored -> candidate);
        });
        List<RuntimeEventDraft> events = new ArrayList<>();
        ConversationRunInputRecorder recorder = new ConversationRunInputRecorder(ledger, events::add);
        var context = AgentContext.builder().sessionId("session").requestId("request").traceId("trace").build();
        context.setAttribute("runId", "run-one");
        context.setAttribute("agentId", "react");
        var a = file("file-a");
        var b = file("file-b");
        recorder.record(context, List.of(b, a));
        recorder.record(context, List.of(b, a));
        assertEquals(2, stored.size());
        assertEquals("{\"inputOrder\":1}", stored.get("conversation-file:input:run-one:file-b").getMetadataJson());
        assertEquals("{\"inputOrder\":2}", stored.get("conversation-file:input:run-one:file-a").getMetadataJson());
        assertEquals("input", stored.get("conversation-file:input:run-one:file-a").getArtifactRole());
        assertEquals("file-b", ((InputFilePayload) events.get(0).getPayload()).getFileId());
        assertEquals(1, ((InputFilePayload) events.get(0).getPayload()).getOrder());
        context.setAttribute("runId", "run-two");
        recorder.record(context, List.of(a));
        assertEquals(3, stored.size());
        assertEquals("run-two", stored.get("conversation-file:input:run-two:file-a").getRunId());
    }

    private ConversationFileRecord file(String id) {
        return new ConversationFileRecord(id, "session", "same.txt", "text/plain", 4,
                "hash", "local_artifact_storage", "key", "READY", Instant.EPOCH, null);
    }
}
