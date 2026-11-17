package com.flocier.application.agent.runtimeevent.history;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch.DeepSearchPresentationMapper;
import com.flocier.domain.ledger.service.replay.rich.RichRuntimeReplayProjectorRegistry;
import com.flocier.domain.ledger.service.replay.rich.deepsearch.DeepSearchRuntimeReplayProjector;
import com.flocier.domain.agent.runtime.event.RuntimeReplayAssembler;
import com.flocier.domain.agent.runtime.event.ToolProgressPayload;
import com.flocier.domain.agent.runtime.event.RuntimeReplayEventMapper;
import com.flocier.domain.ledger.adapter.repository.IRuntimeEventLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.agent.runtime.file.ConversationFileRepository;
import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import com.flocier.domain.agent.runtime.file.ConversationFileSession;
import com.flocier.domain.agent.runtime.event.InputFilePayload;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.domain.ledger.model.replay.ReplayEntryMetadata;
import com.flocier.domain.ledger.model.replay.RunReplayTimeline;
import com.flocier.domain.ledger.model.replay.ToolReplayEntry;
import com.flocier.domain.ledger.service.ExecutionReplayService;
import com.flocier.domain.ledger.service.LedgerQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeHistoryQueryServiceTest {

    @Test
    void localSingleUserHistoryIncludesFileSessionsBeforeAnyFileIsSelected() {
        LedgerQueryService ledgerQuery = mock(LedgerQueryService.class);
        ExecutionReplayService replayService = mock(ExecutionReplayService.class);
        IArtifactLedgerRepository artifacts = mock(IArtifactLedgerRepository.class);
        ConversationFileRepository files = mock(ConversationFileRepository.class);
        var protectedSession = com.flocier.domain.ledger.model.entity.SessionLedgerEntity.builder()
                .sessionId("protected-session").startedAt(Instant.EPOCH).lastActiveAt(Instant.EPOCH).build();
        when(ledgerQuery.queryRecentSessions(any())).thenReturn(List.of(protectedSession));
        when(files.findSession("protected-session")).thenReturn(Optional.of(
                new ConversationFileSession("protected-session", "hash", Instant.EPOCH, Instant.MAX)));
        RuntimeHistoryQueryService service = new RuntimeHistoryQueryService(ledgerQuery, replayService,
                new RuntimeReplayEventMapper(), new RichRuntimeReplayProjectorRegistry(List.of()),
                new RuntimeReplayAssembler(), artifacts, files);
        assertEquals("protected-session", service.listSessions(50).get(0).getSessionId());
    }

    @Test
    void inputReplayRequiresExplicitProjectionAndKeepsOrderAfterRemoval() {
        LedgerQueryService ledgerQuery = mock(LedgerQueryService.class);
        ExecutionReplayService replayService = mock(ExecutionReplayService.class);
        IArtifactLedgerRepository artifacts = mock(IArtifactLedgerRepository.class);
        ConversationFileRepository files = mock(ConversationFileRepository.class);
        RunLedgerEntity run = run("run-files", "session-files", "compare files", Map.of());
        ArtifactLedgerEntity second = ArtifactLedgerEntity.builder().artifactId("input-b")
                .runId(run.getRunId()).artifactRole("input").sourceType("user_upload")
                .sourceId("file-b").fileName("b.txt").mimeType("text/plain").fileSize(2L)
                .metadataJson("{\"inputOrder\":2}").createdAt(Instant.EPOCH).build();
        ArtifactLedgerEntity first = ArtifactLedgerEntity.builder().artifactId("input-a")
                .runId(run.getRunId()).artifactRole("input").sourceType("user_upload")
                .sourceId("file-a").fileName("a.txt").mimeType("text/plain").fileSize(1L)
                .metadataJson("{\"inputOrder\":1}").createdAt(Instant.EPOCH).build();
        when(ledgerQuery.queryRunView(run.getRunId())).thenReturn(Optional.of(
                com.flocier.domain.ledger.model.entity.LedgerRunView.builder().run(run).build()));
        when(replayService.replayRun(run.getRunId())).thenReturn(timeline(run));
        when(artifacts.findByRunId(run.getRunId())).thenReturn(List.of(second, first));
        when(files.findFile("file-a")).thenReturn(Optional.of(new ConversationFileRecord("file-a",
                run.getSessionId(), "a.txt", "text/plain", 1, "hash", "local_artifact_storage",
                "key", "READY", Instant.EPOCH, null)));
        RuntimeHistoryQueryService service = new RuntimeHistoryQueryService(ledgerQuery, replayService,
                new RuntimeReplayEventMapper(), new RichRuntimeReplayProjectorRegistry(List.of()),
                new RuntimeReplayAssembler(), artifacts, files);

        assertFalse(service.findRun(run.getRunId()).orElseThrow().getEvents().stream()
                .anyMatch(event -> "INPUT_FILE".equals(event.getEventType())));
        var events = service.findRun(run.getRunId(), true).orElseThrow().getEvents();
        assertEquals(List.of("INPUT_FILE", "INPUT_FILE", "RUN_COMPLETED"),
                events.stream().map(item -> item.getEventType()).toList());
        assertEquals("file-a", ((InputFilePayload) events.get(0).getPayload()).getFileId());
        assertEquals("AVAILABLE", ((InputFilePayload) events.get(0).getPayload()).getStatus());
        assertEquals("UNAVAILABLE", ((InputFilePayload) events.get(1).getPayload()).getStatus());
        assertEquals(List.of(1L, 2L, 3L), events.stream().map(item -> item.getSequence()).toList());
    }

    @Test
    void runDetailUsesTheFirstClassRunQueryInsteadOfLegacyExtension() {
        LedgerQueryService ledgerQuery = mock(LedgerQueryService.class);
        ExecutionReplayService replayService = mock(ExecutionReplayService.class);
        RunLedgerEntity run = run("run-primary", "session-1", "first-class input", Map.of("queryText", "legacy input"));
        when(ledgerQuery.queryRunView("run-primary"))
                .thenReturn(Optional.of(com.flocier.domain.ledger.model.entity.LedgerRunView.builder().run(run).build()));
        when(replayService.replayRun("run-primary")).thenReturn(timeline(run));

        RuntimeHistoryQueryService service = service(ledgerQuery, replayService, List.of());

        var detail = service.findRun("run-primary").orElseThrow();

        assertEquals("first-class input", detail.getTask());
    }

    @Test
    void sessionReplayNeverUsesLatestSessionQueryForRunsThatDidNotPersistInput() {
        LedgerQueryService ledgerQuery = mock(LedgerQueryService.class);
        ExecutionReplayService replayService = mock(ExecutionReplayService.class);
        RunLedgerEntity incompleteRun = run("run-incomplete", "session-legacy", null, Map.of());
        com.flocier.domain.ledger.model.entity.SessionLedgerEntity session = com.flocier.domain.ledger.model.entity.SessionLedgerEntity.builder()
                .sessionId("session-legacy")
                .latestQueryText("a later question that must not be backfilled")
                .startedAt(Instant.EPOCH)
                .lastActiveAt(Instant.EPOCH)
                .build();
        when(ledgerQuery.querySession("session-legacy")).thenReturn(Optional.of(session));
        when(ledgerQuery.queryRuns(any(LedgerRunQueryEntity.class))).thenReturn(List.of(incompleteRun));
        when(replayService.replaySession(eq("session-legacy"), eq(50))).thenReturn(List.of(timeline(incompleteRun)));

        RuntimeHistoryQueryService service = service(ledgerQuery, replayService, List.of());

        var detail = service.findSession("session-legacy", 50).orElseThrow();

        assertEquals("", detail.getReplays().get(0).getTask());
        assertTrue(detail.getReplays().get(0).isDataIncomplete());
    }

    @Test
    void historyReplayUsesPersistedProgressIdentityBeforeToolTerminal() {
        LedgerQueryService ledgerQuery = mock(LedgerQueryService.class);
        ExecutionReplayService replayService = mock(ExecutionReplayService.class);
        RunLedgerEntity run = run("run-progress", "session-progress", "research", Map.of());
        RuntimeEventLedgerEntity fact = RuntimeEventLedgerEntity.builder()
                .runtimeEventId("runtime-fact-1")
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .requestId(run.getRequestId())
                .toolInvocationId("tool-1")
                .eventType("deep_search_stage")
                .sequenceNo(1)
                .stage("search_completed")
                .status("completed")
                .message("search completed")
                .presentationKind("deep_search.search_results.v1")
                .payloadSchemaVersion("1")
                .payloadJson("{\"iteration\":1,\"queries\":[],\"sources\":[]}")
                .occurredAt(Instant.EPOCH)
                .recordedAt(Instant.EPOCH)
                .build();
        when(ledgerQuery.queryRunView(run.getRunId()))
                .thenReturn(Optional.of(com.flocier.domain.ledger.model.entity.LedgerRunView.builder().run(run).build()));
        when(replayService.replayRun(run.getRunId())).thenReturn(timeline(run));

        var detail = service(ledgerQuery, replayService, List.of(fact)).findRun(run.getRunId()).orElseThrow();

        assertEquals("TOOL_PROGRESS", detail.getEvents().get(0).getEventType());
        assertEquals("runtime-fact-1", detail.getEvents().get(0).getEventId());
        assertTrue(detail.isDataIncomplete());
    }

    @Test
    void historyReplayOrdersPersistedProgressBeforeItsToolResult() {
        LedgerQueryService ledgerQuery = mock(LedgerQueryService.class);
        ExecutionReplayService replayService = mock(ExecutionReplayService.class);
        RunLedgerEntity run = run("run-ordered-progress", "session-progress", "research", Map.of());
        ToolReplayEntry tool = new ToolReplayEntry(
                new ReplayEntryMetadata("tool-entry", 1, Instant.EPOCH, run.getRunId(), false),
                "llm-1",
                "tool-1",
                "call-1",
                1,
                "deep_search_tool",
                "SUCCEEDED",
                "PERSISTED",
                "research intent",
                "research completed",
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1)
        );
        when(ledgerQuery.queryRunView(run.getRunId()))
                .thenReturn(Optional.of(com.flocier.domain.ledger.model.entity.LedgerRunView.builder().run(run).build()));
        when(replayService.replayRun(run.getRunId())).thenReturn(timeline(run, tool));

        var detail = service(ledgerQuery, replayService, List.of(
                fact(run, "runtime-fact-2", 2, "fetch_completed"),
                fact(run, "runtime-fact-1", 1, "search_completed")
        )).findRun(run.getRunId()).orElseThrow();

        assertEquals(List.of("TOOL_STARTED", "TOOL_PROGRESS", "TOOL_PROGRESS", "TOOL_RESULT", "RUN_COMPLETED"),
                detail.getEvents().stream().map(item -> item.getEventType()).toList());
        assertEquals(List.of("runtime-fact-1", "runtime-fact-2"),
                detail.getEvents().subList(1, 3).stream().map(item -> item.getEventId()).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                detail.getEvents().stream().map(item -> item.getSequence()).toList());
    }

    @Test
    void invalidPersistedPayloadIsDowngradedWithoutLeakingRawJson() {
        LedgerQueryService ledgerQuery = mock(LedgerQueryService.class);
        ExecutionReplayService replayService = mock(ExecutionReplayService.class);
        RunLedgerEntity run = run("run-invalid-progress", "session-progress", "research", Map.of());
        RuntimeEventLedgerEntity invalidFact = RuntimeEventLedgerEntity.builder()
                .runtimeEventId("runtime-fact-invalid")
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .requestId(run.getRequestId())
                .toolInvocationId("tool-missing")
                .eventType("deep_search_stage")
                .sequenceNo(1)
                .stage("fetch_completed")
                .status("completed")
                .message("过程已记录")
                .presentationKind("deep_search.unknown.v1")
                .payloadSchemaVersion("1")
                .payloadJson("{\"storageKey\":\"private-key\",\"rawBody\":\"private page body\"}")
                .occurredAt(Instant.EPOCH)
                .recordedAt(Instant.EPOCH)
                .build();
        when(ledgerQuery.queryRunView(run.getRunId()))
                .thenReturn(Optional.of(com.flocier.domain.ledger.model.entity.LedgerRunView.builder().run(run).build()));
        when(replayService.replayRun(run.getRunId())).thenReturn(timeline(run));

        var detail = service(ledgerQuery, replayService, List.of(invalidFact)).findRun(run.getRunId()).orElseThrow();

        assertEquals("TOOL_PROGRESS", detail.getEvents().get(0).getEventType());
        ToolProgressPayload payload = (ToolProgressPayload) detail.getEvents().get(0).getPayload();
        assertEquals("deep_search.presentation_unavailable.v1", payload.getPresentationKind());
        assertEquals(Map.of("reason", "历史过程详情不符合当前展示协议"), payload.getPresentationData());
        assertFalse(JSON.toJSONString(detail.getEvents()).contains("private-key"));
        assertFalse(JSON.toJSONString(detail.getEvents()).contains("private page body"));
        assertTrue(detail.isDataIncomplete());
    }

    private RuntimeHistoryQueryService service(
            LedgerQueryService ledgerQuery,
            ExecutionReplayService replayService,
            List<RuntimeEventLedgerEntity> facts
    ) {
        IRuntimeEventLedgerRepository repository = mock(IRuntimeEventLedgerRepository.class);
        when(repository.findByRunIdOrderByInvocationAndSequenceNo(any())).thenReturn(facts);
        DeepSearchRuntimeReplayProjector projector = new DeepSearchRuntimeReplayProjector(
                repository,
                new DeepSearchPresentationMapper()
        );
        return new RuntimeHistoryQueryService(
                ledgerQuery,
                replayService,
                new RuntimeReplayEventMapper(),
                new RichRuntimeReplayProjectorRegistry(List.of(projector)),
                new RuntimeReplayAssembler(),
                mock(com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository.class),
                mock(com.flocier.domain.agent.runtime.file.ConversationFileRepository.class)
        );
    }

    private RunReplayTimeline timeline(RunLedgerEntity run) {
        return RunReplayTimeline.builder()
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .entries(List.of())
                .build();
    }

    private RunReplayTimeline timeline(RunLedgerEntity run, ToolReplayEntry tool) {
        return RunReplayTimeline.builder()
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .entries(List.of(tool))
                .build();
    }

    private RuntimeEventLedgerEntity fact(
            RunLedgerEntity run,
            String runtimeEventId,
            int sequenceNo,
            String stage
    ) {
        return RuntimeEventLedgerEntity.builder()
                .runtimeEventId(runtimeEventId)
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .requestId(run.getRequestId())
                .toolInvocationId("tool-1")
                .eventType("deep_search_stage")
                .sequenceNo(sequenceNo)
                .stage(stage)
                .status("completed")
                .message(stage)
                .presentationKind("deep_search.search_results.v1")
                .payloadSchemaVersion("1")
                .payloadJson("{\"iteration\":1,\"queries\":[],\"sources\":[]}")
                .occurredAt(Instant.EPOCH)
                .recordedAt(Instant.EPOCH)
                .build();
    }

    private RunLedgerEntity run(String runId, String sessionId, String queryText, Map<String, Object> ext) {
        return RunLedgerEntity.builder()
                .runId(runId)
                .sessionId(sessionId)
                .requestId("request-" + runId)
                .agentId("react-runtime")
                .status("COMPLETED")
                .queryText(queryText)
                .ext(ext)
                .startedAt(Instant.EPOCH)
                .finishedAt(Instant.EPOCH)
                .build();
    }
}
