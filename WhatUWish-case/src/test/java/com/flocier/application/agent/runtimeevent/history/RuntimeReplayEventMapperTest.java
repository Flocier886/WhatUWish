package com.flocier.application.agent.runtimeevent.history;

import com.flocier.domain.agent.runtime.event.ArtifactCreatedPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.LlmResponsePayload;
import com.flocier.domain.agent.runtime.event.RuntimeReplayEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeReplayProjection;
import com.flocier.domain.agent.runtime.event.ToolResultPayload;
import com.flocier.domain.agent.runtime.event.ToolStartedPayload;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.model.replay.ArtifactReplayEntry;
import com.flocier.domain.ledger.model.replay.CompletedReplayEntry;
import com.flocier.domain.ledger.model.replay.FailedReplayEntry;
import com.flocier.domain.ledger.model.replay.LlmReplayEntry;
import com.flocier.domain.ledger.model.replay.ReplayEntryMetadata;
import com.flocier.domain.ledger.model.replay.RunReplayTimeline;
import com.flocier.domain.ledger.model.replay.ToolReplayEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeReplayEventMapperTest {

    private final RuntimeReplayEventMapper mapper = new RuntimeReplayEventMapper();

    @Test
    void failedRunHasOnlyFailedTerminalAndArtifactEntryCannotCarryStorageReference() {
        RunLedgerEntity run = run("FAILED", null);
        ArtifactReplayEntry artifact = new ArtifactReplayEntry(metadata("artifact:a-1", 1), null, "tool-1", "a-1",
                "report.md", "file", "text/markdown", 128L, "report", "visible", "tool-1");
        FailedReplayEntry failure = new FailedReplayEntry(metadata("failure:run-1", 2), null, "tool failed");
        CompletedReplayEntry completed = new CompletedReplayEntry(metadata("final:run-1", 3), "must be suppressed", "fallback");

        RuntimeReplayProjection projection = mapper.project(run, timeline(artifact, failure, completed));

        assertEquals(List.of(RuntimeEventType.ARTIFACT_CREATED, RuntimeEventType.RUN_FAILED),
                projection.getEvents().stream().map(item -> item.getEventType()).toList());
        ArtifactCreatedPayload payload = (ArtifactCreatedPayload) projection.getEvents().get(0).getPayload();
        assertEquals("a-1", payload.getReference());
        assertFalse(String.valueOf(payload).contains("storage"));
        assertFalse(String.valueOf(payload).contains("private-key"));
    }

    @Test
    void typedLlmAndToolEntriesPreserveKnownFieldsWithoutMapKeyParsing() {
        LlmReplayEntry llm = new LlmReplayEntry(metadata("llm:l-1", 1), "l-1", "gpt-test", "react", "I will use a tool.");
        ToolReplayEntry tool = new ToolReplayEntry(metadata("tool:t-1", 2), "l-1", "t-1", "call-1", 3,
                "web_fetch", "SUCCESS", "PERSISTED", "{\"url\":\"https://example.test\"}", "fetched", null, null,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        CompletedReplayEntry completed = new CompletedReplayEntry(metadata("final:run-1", 3), "done", "explicit");

        RuntimeReplayProjection projection = mapper.project(run("COMPLETED", "done"), timeline(llm, tool, completed));

        assertEquals(List.of(RuntimeEventType.LLM_RESPONSE, RuntimeEventType.TOOL_STARTED,
                        RuntimeEventType.TOOL_RESULT, RuntimeEventType.RUN_COMPLETED),
                projection.getEvents().stream().map(item -> item.getEventType()).toList());
        ToolStartedPayload started = (ToolStartedPayload) projection.getEvents().get(1).getPayload();
        assertEquals("RUNNING", started.getStatus());
        ToolResultPayload payload = (ToolResultPayload) projection.getEvents().get(2).getPayload();
        assertEquals("call-1", payload.getToolCallId());
        assertEquals(3, payload.getStepNo());
        assertEquals("web_fetch", payload.getToolName());
        assertEquals("fetched", payload.getObservation());
    }

    @Test
    void completedRunWithoutTerminalEntryReceivesSingleFallbackConclusion() {
        RuntimeReplayProjection projection = mapper.project(run("COMPLETED", "ledger summary"), timeline());

        assertEquals(1, projection.getEvents().size());
        assertEquals(RuntimeEventType.RUN_COMPLETED, projection.getEvents().get(0).getEventType());
        assertTrue(projection.isDataIncomplete());
    }

    @Test
    void artifactReadNeverProjectsItsObservationOrStorageLocatorToReplay() {
        ToolReplayEntry tool = new ToolReplayEntry(metadata("tool:read-1", 1), "llm-1", "read-1", "call-read", 1,
                "artifact_read", "SUCCESS", "PERSISTED",
                "{\"storageKey\":\"private/run/report.md\"}",
                "storageKey: private/run/report.md\nsecret artifact body", null, null,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1));

        RuntimeReplayProjection projection = mapper.project(run("COMPLETED", "done"), timeline(tool));

        ToolStartedPayload started = (ToolStartedPayload) projection.getEvents().get(0).getPayload();
        ToolResultPayload payload = (ToolResultPayload) projection.getEvents().get(1).getPayload();
        assertFalse(started.getArgumentsSummary().contains("private/run/report.md"));
        assertFalse(payload.getArgumentsSummary().contains("private/run/report.md"));
        assertFalse(payload.getObservation().contains("secret artifact body"));
        assertFalse(payload.getObservation().contains("private/run/report.md"));
    }

    @Test
    void runningInvocationKeepsStartedFactWithoutFabricatedResult() {
        ToolReplayEntry tool = new ToolReplayEntry(metadata("tool:running-1", 1), "llm-1", "running-1", "call-running", 1,
                "web_fetch", "RUNNING", "PERSISTED", "{\"url\":\"https://example.test\"}", null, null, null,
                Instant.EPOCH, null);

        RuntimeReplayProjection projection = mapper.project(run("COMPLETED", "done"), timeline(tool));

        assertEquals(List.of(RuntimeEventType.TOOL_STARTED, RuntimeEventType.RUN_COMPLETED),
                projection.getEvents().stream().map(item -> item.getEventType()).toList());
        assertTrue(projection.isDataIncomplete());
    }

    @Test
    void partialSuccessStatusIsPreservedInReplay() {
        ToolReplayEntry tool = new ToolReplayEntry(metadata("tool:partial-1", 1), "llm-1", "partial-1", "call-partial", 1,
                "file_search", "partial_success", "PERSISTED", "{\"query\":\"ledger\"}", "no matches", null, null,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1));

        RuntimeReplayProjection projection = mapper.project(run("COMPLETED", "done"), timeline(tool));

        ToolResultPayload result = (ToolResultPayload) projection.getEvents().get(1).getPayload();
        assertEquals("PARTIAL_SUCCESS", result.getStatus());
    }

    @Test
    void artifactReadNeverProjectsItsObservationOrStorageLocatorToLiveEvent() {
        ToolInvocationLedgerEntity invocation = ToolInvocationLedgerEntity.builder()
                .toolInvocationId("read-1").toolCallId("call-read").toolName("artifact_read")
                .status("SUCCESS").requestSummary("{\"storageKey\":\"private/run/report.md\"}")
                .sessionId("session-1").runId("run-1").requestId("request-1").build();
        ToolExecutionResult result = ToolExecutionResult.builder().toolName("artifact_read").toolCallId("call-read")
                .llmObservation("storageKey: private/run/report.md\nsecret artifact body").build();

        ToolResultPayload payload = (ToolResultPayload) new RuntimeEventMapper().toolResult(invocation, result).getPayload();

        assertFalse(payload.getArgumentsSummary().contains("private/run/report.md"));
        assertFalse(payload.getObservation().contains("secret artifact body"));
        assertFalse(payload.getObservation().contains("private/run/report.md"));
    }

    @Test
    void toolStartedOnlyProjectsPersistedRunningInvocationAndRedactsArguments() {
        ToolInvocationLedgerEntity running = ToolInvocationLedgerEntity.builder()
                .toolInvocationId("read-start-1").toolCallId("call-read-start").toolName("artifact_read")
                .status("RUNNING").requestSummary("{\"storageKey\":\"private/run/report.md\"}")
                .startedAt(Instant.EPOCH).sessionId("session-1").runId("run-1").requestId("request-1").build();
        ToolInvocationLedgerEntity finished = running.toBuilder().status("SUCCESS").finishedAt(Instant.EPOCH).build();

        ToolStartedPayload payload = (ToolStartedPayload) new RuntimeEventMapper().toolStarted(running).getPayload();

        assertEquals("RUNNING", payload.getStatus());
        assertFalse(payload.getArgumentsSummary().contains("private/run/report.md"));
        assertEquals(null, new RuntimeEventMapper().toolStarted(finished));
        assertEquals(null, new RuntimeEventMapper().toolStarted(null));
    }

    @Test
    void artifactSavingArgumentsAndInlineStorageLocatorNeverReachLiveEvent() {
        ToolInvocationLedgerEntity invocation = ToolInvocationLedgerEntity.builder()
                .toolInvocationId("save-1").toolCallId("call-save").toolName("artifact_save")
                .status("SUCCESS").requestSummary("{\"content\":\"secret artifact body\"}")
                .sessionId("session-1").runId("run-1").requestId("request-1").build();
        ToolExecutionResult result = ToolExecutionResult.builder().toolName("artifact_save").toolCallId("call-save")
                .llmObservation("已保存产物。\n关联产物：artifact_read(storageKey=private/run/report.md)").build();

        ToolResultPayload payload = (ToolResultPayload) new RuntimeEventMapper().toolResult(invocation, result).getPayload();

        assertFalse(payload.getArgumentsSummary().contains("secret artifact body"));
        assertFalse(payload.getObservation().contains("private/run/report.md"));
    }

    @Test
    void completedSummaryDoesNotExposePrivateStorageLocator() {
        CompletedReplayEntry completed = new CompletedReplayEntry(metadata("final:run-1", 1),
                "交付完成。\n- storageKey：private/run/report.md", "fallback");

        RuntimeReplayProjection projection = mapper.project(run("COMPLETED", "done"), timeline(completed));

        String summary = ((com.flocier.domain.agent.runtime.event.RunCompletedPayload) projection.getEvents().get(0).getPayload())
                .getSummary();
        assertFalse(summary.contains("private/run/report.md"));
    }

    @Test
    void llmResponseDoesNotExposePrivateStorageLocator() {
        LlmReplayEntry llm = new LlmReplayEntry(metadata("llm:1", 1), "llm-1", "gpt-test", "summary",
                "交付完成。\n- storageKey：private/run/report.md");

        RuntimeReplayProjection projection = mapper.project(run("COMPLETED", "done"), timeline(llm));

        String text = ((LlmResponsePayload) projection.getEvents().get(0).getPayload()).getText();
        assertFalse(text.contains("private/run/report.md"));
    }

    private RunReplayTimeline timeline(com.flocier.domain.ledger.model.replay.ReplayTimelineEntry... entries) {
        return RunReplayTimeline.builder().runId("run-1").sessionId("session-1").entries(List.of(entries)).build();
    }

    private ReplayEntryMetadata metadata(String eventId, int sequence) {
        return new ReplayEntryMetadata(eventId, sequence, Instant.EPOCH, "run-1", false);
    }

    private RunLedgerEntity run(String status, String summary) {
        return RunLedgerEntity.builder().runId("run-1").sessionId("session-1").requestId("request-1")
                .agentId("react-runtime").status(status).summary(summary).startedAt(Instant.EPOCH).finishedAt(Instant.EPOCH).build();
    }
}
