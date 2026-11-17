package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.model.entity.LedgerMemoryBuildCommandEntity;
import com.flocier.domain.agent.model.entity.SessionMemoryView;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.response.LlmUsage;
import com.flocier.domain.agent.runtime.memory.LedgerMemoryExtractor;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.ledger.service.LedgerQueryService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.domain.ledger.service.ExecutionLedgerReadService;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class LedgerArtifactSemanticsTest {

    @Test
    public void shouldKeepPlainLlmResponseInLlmInvocationLedgerOnly() {
        Fixture fixture = new Fixture();

        fixture.recordingService.recordLlmCall(
                LlmRequest.builder()
                        .model("gpt-4o-mini")
                        .messages(List.of(LlmMessage.builder()
                                .role(LlmMessageRole.USER)
                                .content("hello")
                                .build()))
                        .attributes(Map.of(
                                "runId", "run-plain-llm",
                                "requestId", "req-plain-llm",
                                "sessionId", "session-plain-llm",
                                "traceId", "trace-plain-llm",
                                "scene", "summary",
                                "step", 1
                        ))
                        .build(),
                LlmResponse.builder()
                        .model("gpt-4o-mini")
                        .provider("default-openai")
                        .text("plain llm response")
                        .usage(LlmUsage.builder().promptTokens(1).completionTokens(2).totalTokens(3).build())
                        .build()
        );

        Assert.assertEquals(1, fixture.llmRepository.findByRunId("run-plain-llm").size());
        Assert.assertEquals("plain llm response", fixture.llmRepository.findByRunId("run-plain-llm").get(0).getResponseSummary());
        Assert.assertTrue(fixture.artifactRepository.findByRunId("run-plain-llm").isEmpty());
    }

    @Test
    public void shouldKeepPlainToolObservationInToolInvocationLedgerOnly() {
        Fixture fixture = new Fixture();
        AgentContext context = fixture.context("run-plain-tool", "req-plain-tool", "session-plain-tool", "trace-plain-tool");

        fixture.recordingService.recordRunStarted(context, "agent-ledger-test", "test");
        fixture.recordingService.recordToolCall(
                context,
                ToolInvokeRequest.builder()
                        .toolName("echo_tool")
                        .toolCallId("tool-call-plain")
                        .arguments(Map.of("message", "hello"))
                        .requestId("req-plain-tool")
                        .sessionId("session-plain-tool")
                        .step(1)
                        .build(),
                ToolExecutionResult.builder()
                        .toolName("echo_tool")
                        .toolCallId("tool-call-plain")
                        .sourceType("java")
                        .success(true)
                        .status("SUCCESS")
                        .outputText("hello")
                        .llmObservation("echo result: hello")
                        .structuredOutput(Map.of("message", "hello"))
                        .finishedAt(Instant.now())
                        .durationMs(10L)
                        .build()
        );

        LedgerRunView runView = fixture.queryService.queryRunView("run-plain-tool").orElse(null);
        Assert.assertNotNull(runView);
        Assert.assertEquals(1, runView.getToolInvocations().size());
        Assert.assertEquals("tool-call-plain", runView.getToolInvocations().get(0).getToolCallId());
        Assert.assertEquals("echo result: hello", runView.getToolInvocations().get(0).getResponseSummary());
        Assert.assertTrue(String.valueOf(runView.getToolInvocations().get(0).getExt().get("structuredOutput")).contains("hello"));
        Assert.assertTrue(runView.getArtifacts().isEmpty());
    }

    @Test
    public void shouldKeepToolArtifactRefsInToolLedgerWithoutCreatingArtifactRows() {
        Fixture fixture = new Fixture();
        AgentContext context = fixture.context("run-tool-ref", "req-tool-ref", "session-tool-ref", "trace-tool-ref");

        fixture.recordingService.recordRunStarted(context, "agent-ledger-test", "test");
        fixture.recordingService.recordToolCall(
                context,
                ToolInvokeRequest.builder()
                        .toolName("python_tool")
                        .toolCallId("tool-call-ref")
                        .arguments(Map.of("script", "print('done')"))
                        .requestId("req-tool-ref")
                        .sessionId("session-tool-ref")
                        .step(1)
                        .build(),
                ToolExecutionResult.builder()
                        .toolName("python_tool")
                        .toolCallId("tool-call-ref")
                        .sourceType("python")
                        .success(true)
                        .status("SUCCESS")
                        .outputText("script finished")
                        .artifactRefs(List.of("artifacts/run-tool-ref/output.txt"))
                        .finishedAt(Instant.now())
                        .durationMs(20L)
                        .build()
        );

        LedgerRunView runView = fixture.queryService.queryRunView("run-tool-ref").orElse(null);
        Assert.assertNotNull(runView);
        Assert.assertEquals("artifacts/run-tool-ref/output.txt", runView.getToolInvocations().get(0).getArtifactRef());
        Assert.assertTrue(String.valueOf(runView.getToolInvocations().get(0).getExt().get("artifactRefs"))
                .contains("artifacts/run-tool-ref/output.txt"));
        Assert.assertTrue(runView.getArtifacts().isEmpty());
    }

    @Test
    public void shouldRecordExplicitReusableArtifact() {
        Fixture fixture = new Fixture();

        fixture.recordingService.recordArtifact(reusableArtifact(
                "artifact-real-report",
                "run-real-artifact",
                "req-real-artifact",
                "session-real-artifact",
                "trace-real-artifact",
                "tool_output",
                "tool-call-real",
                "report.md",
                "artifacts/run-real-artifact/report.md"
        ));

        List<ArtifactLedgerEntity> artifacts = fixture.artifactRepository.findByRunId("run-real-artifact");
        Assert.assertEquals(1, artifacts.size());
        Assert.assertEquals("file", artifacts.get(0).getArtifactType());
        Assert.assertEquals("file_ref", artifacts.get(0).getStorageType());
        Assert.assertEquals("artifacts/run-real-artifact/report.md", artifacts.get(0).getStorageRef());
    }

    @Test
    public void shouldRecordArtifactSchemaFieldsAndQueryByToolOwnership() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();

        fixture.recordingService.recordArtifact(ArtifactLedgerEntity.builder()
                .artifactId("artifact-schema-file")
                .runId("run-schema")
                .requestId("req-schema")
                .sessionId("session-schema")
                .traceId("trace-schema")
                .toolInvocationId("tool-invocation-schema")
                .toolCallId("tool-call-schema")
                .artifactRole("output")
                .visibility("visible")
                .sourceType("skill_generated_artifact")
                .sourceId("skill-execution-schema")
                .sourceName("skill_script_runner")
                .artifactType("file")
                .artifactName("legacy-summary.md")
                .fileName("summary.md")
                .storageType("object_key")
                .storageKey("artifacts/run-schema/summary.md")
                .downloadUrl("https://example.test/download/summary.md")
                .previewUrl("https://example.test/preview/summary.md")
                .contentSummary("generated summary")
                .mimeType("text/markdown")
                .fileSize(128L)
                .fileHash("sha256:abc")
                .metadataJson("{\"skill\":\"script\"}")
                .idempotencyKey("artifact:schema:summary")
                .createdAt(now)
                .updatedAt(now)
                .build());

        List<ArtifactLedgerEntity> byToolInvocation = fixture.artifactRepository.findByToolInvocationId("tool-invocation-schema");
        List<ArtifactLedgerEntity> byRunAndToolCall = fixture.artifactRepository.findByRunIdAndToolCallId("run-schema", "tool-call-schema");
        List<ArtifactLedgerEntity> byRequestAndToolCall = fixture.artifactRepository.findByRequestIdAndToolCallId("req-schema", "tool-call-schema");

        Assert.assertEquals(1, byToolInvocation.size());
        Assert.assertEquals(1, byRunAndToolCall.size());
        Assert.assertEquals(1, byRequestAndToolCall.size());
        ArtifactLedgerEntity artifact = byToolInvocation.get(0);
        Assert.assertEquals("tool-invocation-schema", artifact.getToolInvocationId());
        Assert.assertEquals("tool-call-schema", artifact.getToolCallId());
        Assert.assertEquals("output", artifact.getArtifactRole());
        Assert.assertEquals("visible", artifact.getVisibility());
        Assert.assertEquals("skill_script_runner", artifact.getSourceName());
        Assert.assertEquals("summary.md", artifact.getFileName());
        Assert.assertEquals("artifacts/run-schema/summary.md", artifact.getStorageKey());
        Assert.assertEquals("https://example.test/download/summary.md", artifact.getDownloadUrl());
        Assert.assertEquals("https://example.test/preview/summary.md", artifact.getPreviewUrl());
        Assert.assertEquals(Long.valueOf(128L), artifact.getFileSize());
        Assert.assertEquals("sha256:abc", artifact.getFileHash());
        Assert.assertEquals("{\"skill\":\"script\"}", artifact.getMetadataJson());
    }

    @Test
    public void shouldAcceptStorageKeyOrDownloadUrlAsStableArtifactLocation() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();

        fixture.recordingService.recordArtifact(ArtifactLedgerEntity.builder()
                .artifactId("artifact-storage-key-only")
                .runId("run-location")
                .requestId("req-location")
                .traceId("trace-location")
                .toolInvocationId("tool-invocation-location")
                .toolCallId("tool-call-location")
                .sourceType("tool_output")
                .sourceName("python_tool")
                .artifactType("file")
                .fileName("key-only.json")
                .storageType("object_key")
                .storageKey("artifacts/run-location/key-only.json")
                .idempotencyKey("artifact:location:key")
                .createdAt(now)
                .updatedAt(now)
                .build());
        fixture.recordingService.recordArtifact(ArtifactLedgerEntity.builder()
                .artifactId("artifact-download-url-only")
                .runId("run-location")
                .requestId("req-location")
                .traceId("trace-location")
                .toolInvocationId("tool-invocation-location")
                .toolCallId("tool-call-location")
                .sourceType("tool_output")
                .sourceName("python_tool")
                .artifactType("file")
                .fileName("url-only.json")
                .storageType("url")
                .downloadUrl("https://example.test/download/url-only.json")
                .idempotencyKey("artifact:location:url")
                .createdAt(now)
                .updatedAt(now)
                .build());

        List<ArtifactLedgerEntity> artifacts = fixture.artifactRepository.findByRunIdAndToolCallId("run-location", "tool-call-location");
        Assert.assertEquals(2, artifacts.size());
        Assert.assertTrue(artifacts.stream().anyMatch(item -> "artifacts/run-location/key-only.json".equals(item.getStorageKey())));
        Assert.assertTrue(artifacts.stream().anyMatch(item -> "https://example.test/download/url-only.json".equals(item.getDownloadUrl())));
    }

    @Test
    public void shouldHideDeletedArtifactsFromDefaultQueries() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();

        fixture.artifactRepository.save(ArtifactLedgerEntity.builder()
                .artifactId("artifact-deleted")
                .runId("run-deleted")
                .requestId("req-deleted")
                .traceId("trace-deleted")
                .toolInvocationId("tool-invocation-deleted")
                .toolCallId("tool-call-deleted")
                .sourceType("tool_output")
                .artifactType("file")
                .fileName("deleted.txt")
                .storageKey("artifacts/run-deleted/deleted.txt")
                .deleted(1)
                .deletedAt(now)
                .idempotencyKey("artifact:deleted")
                .createdAt(now)
                .updatedAt(now)
                .build());

        Assert.assertTrue(fixture.artifactRepository.findByRunId("run-deleted").isEmpty());
        Assert.assertTrue(fixture.artifactRepository.findByToolInvocationId("tool-invocation-deleted").isEmpty());
        Assert.assertTrue(fixture.artifactRepository.findByRunIdAndToolCallId("run-deleted", "tool-call-deleted").isEmpty());
        Assert.assertTrue(fixture.artifactRepository.findByRequestIdAndToolCallId("req-deleted", "tool-call-deleted").isEmpty());
    }

    @Test
    public void shouldRejectLegacyPseudoArtifactWrites() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();

        fixture.recordingService.recordArtifact(ArtifactLedgerEntity.builder()
                .artifactId("artifact-pseudo-tool")
                .runId("run-pseudo")
                .requestId("req-pseudo")
                .sessionId("session-pseudo")
                .traceId("trace-pseudo")
                .sourceType("tool")
                .sourceId("tool-call-pseudo")
                .artifactType("tool_result")
                .artifactName("echo_tool")
                .storageType("inline_summary")
                .storageRef("echo_tool")
                .contentSummary("plain tool observation")
                .mimeType("text/plain")
                .idempotencyKey("artifact:pseudo:tool")
                .createdAt(now)
                .updatedAt(now)
                .build());

        Assert.assertTrue(fixture.artifactRepository.findByRunId("run-pseudo").isEmpty());
    }

    @Test
    public void shouldIgnoreLegacyPseudoArtifactsWhenExtractingMemory() {
        Fixture fixture = new Fixture();
        AgentContext context = fixture.context("run-memory-artifact", "req-memory-artifact", "session-memory-artifact", "trace-memory-artifact");
        Instant now = Instant.now();

        fixture.recordingService.recordRunStarted(context, "agent-ledger-test", "test");
        fixture.recordingService.recordRunFinished(context, "COMPLETED", "done", null, null);
        fixture.artifactRepository.save(reusableArtifact(
                "artifact-memory-real",
                "run-memory-artifact",
                "req-memory-artifact",
                "session-memory-artifact",
                "trace-memory-artifact",
                "tool_output",
                "tool-call-real",
                "real-report.md",
                "artifacts/run-memory-artifact/real-report.md"
        ));
        fixture.artifactRepository.save(ArtifactLedgerEntity.builder()
                .artifactId("artifact-memory-legacy")
                .runId("run-memory-artifact")
                .requestId("req-memory-artifact")
                .sessionId("session-memory-artifact")
                .traceId("trace-memory-artifact")
                .sourceType("tool")
                .sourceId("tool-call-legacy")
                .artifactType("tool_result")
                .artifactName("legacy observation")
                .storageType("inline_summary")
                .storageRef("legacy_tool")
                .contentSummary("legacy plain observation")
                .mimeType("text/plain")
                .idempotencyKey("artifact:legacy:tool")
                .createdAt(now)
                .updatedAt(now)
                .build());

        SessionMemoryView memoryView = fixture.memoryExtractor.extract(LedgerMemoryBuildCommandEntity.builder()
                .sessionId("session-memory-artifact")
                .currentRunId("other-run")
                .build());

        Assert.assertTrue(memoryView.getSessionHistory().getRuns().stream()
                .flatMap(run -> run.getUnassociatedArtifacts().stream())
                .anyMatch(item -> "artifact-memory-real".equals(item.getArtifactId())));
    }

    private ArtifactLedgerEntity reusableArtifact(String artifactId,
                                                  String runId,
                                                  String requestId,
                                                  String sessionId,
                                                  String traceId,
                                                  String sourceType,
                                                  String sourceId,
                                                  String artifactName,
                                                  String storageRef) {
        Instant now = Instant.now();
        return ArtifactLedgerEntity.builder()
                .artifactId(artifactId)
                .runId(runId)
                .requestId(requestId)
                .sessionId(sessionId)
                .traceId(traceId)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .artifactType("file")
                .artifactName(artifactName)
                .storageType("file_ref")
                .storageRef(storageRef)
                .contentSummary("reusable generated file")
                .mimeType("text/markdown")
                .size(64L)
                .idempotencyKey("artifact:file:" + runId + ":" + artifactId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static class Fixture {

        private final InMemorySessionLedgerRepository sessionRepository = new InMemorySessionLedgerRepository();
        private final InMemoryRunLedgerRepository runRepository = new InMemoryRunLedgerRepository();
        private final InMemoryLlmInvocationLedgerRepository llmRepository = new InMemoryLlmInvocationLedgerRepository();
        private final InMemoryToolInvocationLedgerRepository toolRepository = new InMemoryToolInvocationLedgerRepository();
        private final InMemoryArtifactLedgerRepository artifactRepository = new InMemoryArtifactLedgerRepository();
        private final LedgerRecordingService recordingService = new LedgerRecordingService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );
        private final LedgerQueryService queryService = new LedgerQueryService(
                sessionRepository,
                runRepository,
                llmRepository,
                toolRepository,
                artifactRepository
        );
        private final LedgerMemoryExtractor memoryExtractor = new LedgerMemoryExtractor(queryService,
                new ExecutionLedgerReadService(runRepository, llmRepository, toolRepository, artifactRepository));

        private AgentContext context(String runId, String requestId, String sessionId, String traceId) {
            AgentContext context = AgentContext.builder()
                    .requestId(requestId)
                    .sessionId(sessionId)
                    .traceId(traceId)
                    .currentStep(1)
                    .build();
            context.setAttribute("runId", runId);
            return context;
        }
    }
}
