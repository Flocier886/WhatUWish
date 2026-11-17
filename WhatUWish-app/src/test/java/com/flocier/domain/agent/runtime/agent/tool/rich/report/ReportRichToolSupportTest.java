package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeLedgerRecorder;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationService;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.event.ReportContentDeltaPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryReportExecutionRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRuntimeEventLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;

/** Report 协议、输入授权、专属投影和 ReAct 返回边界的定向回归。 */
public class ReportRichToolSupportTest {

    @Test
    public void shouldConsumeOrderedReportSseAndRejectInvalidArtifactStage() {
        HttpReportStreamClient client = new HttpReportStreamClient(HttpClient.newHttpClient());
        String progress = "event: progress\n"
                + "data: {\"schemaVersion\":\"report_progress_event.v1\",\"eventId\":\"evt-1\",\"executionId\":\"execution-1\",\"sequence\":1,\"phase\":\"inputs_resolved\",\"status\":\"running\",\"occurredAt\":\"2026-08-28T10:00:00Z\",\"summary\":\"inputs\",\"data\":{}}\n";
        String delta = "event: delta\n"
                + "data: {\"schemaVersion\":\"report_content_delta.v1\",\"executionId\":\"execution-1\",\"outputType\":\"markdown\",\"chunkIndex\":1,\"content\":\"draft\"}\n";
        String artifact = "event: artifact\n"
                + "data: {\"schemaVersion\":\"report_progress_event.v1\",\"eventId\":\"evt-2\",\"executionId\":\"execution-1\",\"sequence\":2,\"phase\":\"artifact_created\",\"status\":\"running\",\"occurredAt\":\"2026-08-28T10:00:01Z\",\"summary\":\"artifact\",\"data\":{\"artifact\":{\"schemaVersion\":\"report_artifact.v1\"}}}\n";
        String result = "event: result\n"
                + "data: {\"schemaVersion\":\"report_result.v1\",\"executionId\":\"execution-1\",\"reportExecutionId\":\"python-report-1\",\"status\":\"completed\",\"summary\":\"done\",\"outputType\":\"markdown\",\"artifact\":{\"schemaVersion\":\"report_artifact.v1\",\"artifactId\":\"artifact-out\"}}\n";

        List<ReportStreamEvent> progressEvents = new ArrayList<>();
        List<ReportContentDelta> deltas = new ArrayList<>();
        ReportFinalResult finalResult = client.consume(
                sseLines(progress, delta, artifact, result),
                "execution-1",
                progressEvents::add,
                deltas::add
        );

        Assert.assertEquals("completed", finalResult.getStatus());
        Assert.assertEquals(2, progressEvents.size());
        Assert.assertEquals(1, deltas.size());

        try {
            client.consume(
                    sseLines(progress.replace("\"sequence\":1", "\"sequence\":2")),
                    "execution-1",
                    ignored -> { },
                    ignored -> { }
            );
            Assert.fail("缺少序号 1 的稳定事件必须被拒绝");
        } catch (ReportStreamException exception) {
            Assert.assertEquals("report_protocol_error", exception.getErrorCode());
        }
    }

    @Test
    public void shouldResolveVisibleTextArtifactsFromCurrentSession() {
        InMemoryArtifactLedgerRepository artifacts = new InMemoryArtifactLedgerRepository();
        artifacts.save(inputArtifact("input-1", "run-1", "session-1", "text/markdown", "notes.md", 128L));
        ReportInputArtifactResolver resolver = new ReportInputArtifactResolver(artifacts);
        AgentContext context = context();
        ReportStartRequest request = startRequest(List.of("input-1"));

        List<ReportInputArtifactDescriptor> resolved = resolver.resolve(context, request);

        Assert.assertEquals(1, resolved.size());
        Assert.assertEquals("input-1", resolved.get(0).getArtifactId());
        Assert.assertEquals("private/input-1", resolved.get(0).getStorageKey());

        artifacts.save(inputArtifact("input-image", "run-1", "session-1", "image/png", "image.png", 128L));
        try {
            resolver.resolve(context, startRequest(List.of("input-image")));
            Assert.fail("二进制 Artifact 不得作为首批 Report 输入");
        } catch (ReportStreamException exception) {
            Assert.assertEquals("report_input_unsupported", exception.getErrorCode());
        }

        artifacts.save(inputArtifact("input-other-run", "run-2", "session-1", "text/plain", "other.txt", 128L));
        List<ReportInputArtifactDescriptor> historicalResolved = resolver.resolve(
                context,
                startRequest(List.of("input-other-run"))
        );
        Assert.assertEquals(List.of("input-other-run"), historicalResolved.stream()
                .map(ReportInputArtifactDescriptor::getArtifactId)
                .toList());

        artifacts.save(inputArtifact("input-other-session", "run-2", "session-2", "text/plain", "other-session.txt", 128L));
        try {
            resolver.resolve(context, startRequest(List.of("input-other-session")));
            Assert.fail("其他会话的 Artifact 不得作为 Report 输入");
        } catch (ReportStreamException exception) {
            Assert.assertEquals("report_input_forbidden", exception.getErrorCode());
        }
    }

    @Test
    public void shouldDiscoverOnlyCurrentRunDeepSearchResearchArtifacts() {
        AgentContext context = context();
        registerResearchArtifact(context, "research-1", "run-1", "deep_search_tool", "research_source_package", "output", "visible");
        registerResearchArtifact(context, "report-1", "run-1", "report_tool", "report", "output", "visible");
        registerResearchArtifact(context, "research-other-run", "run-2", "deep_search_tool", "research_source_package", "output", "visible");
        registerResearchArtifact(context, "research-internal", "run-1", "deep_search_tool", "research_source_package", "output", "internal");
        registerResearchArtifact(context, "research-input", "run-1", "deep_search_tool", "research_source_package", "input", "visible");

        List<String> candidates = new ReportInputArtifactCandidateResolver().resolveCurrentResearchArtifactIds(context);

        Assert.assertEquals(List.of("research-1"), candidates);
    }

    @Test
    public void shouldAutomaticallyResolveCurrentResearchArtifactForReport() {
        Fixture fixture = new Fixture();
        fixture.artifacts.save(inputArtifact("research-1", "run-1", "session-1", "text/markdown", "research.md", 128L));
        registerResearchArtifact(fixture.context, "research-1", "run-1", "deep_search_tool", "research_source_package", "output", "visible");
        ReportStreamClient client = (service, endpoint, request, eventConsumer, deltaConsumer) -> {
            Assert.assertEquals(List.of("research-1"), request.getInputArtifactIds());
            Assert.assertEquals("research-1", request.getInputArtifacts().get(0).getArtifactId());
            eventConsumer.accept(event(request.getExecutionId(), 1, "inputs_resolved", "running", "inputs", Map.of("inputArtifactCount", 1)));
            eventConsumer.accept(event(request.getExecutionId(), 2, "generation_started", "running", "generation", Map.of("outputType", "markdown")));
            eventConsumer.accept(event(request.getExecutionId(), 3, "artifact_created", "running", "artifact", Map.of("artifact", outputArtifact())));
            eventConsumer.accept(event(request.getExecutionId(), 4, "completed", "completed", "报告交付完成", Map.of("outputType", "markdown")));
            return finalResult(request.getExecutionId());
        };
        ReportTool tool = reportTool(fixture, client);

        ToolExecutionResult result = tool.executeRich(automaticRequest(), fixture.context);

        Assert.assertTrue(result.isSuccess());
        String invocationId = fixture.tools.findByRunId("run-1").get(0).getToolInvocationId();
        String reportExecutionId = fixture.executions.findByToolInvocationId(invocationId).orElseThrow().getReportExecutionId();
        Assert.assertEquals(List.of("research-1"), fixture.executions.findInputsByReportExecutionId(reportExecutionId).stream()
                .map(item -> item.getArtifactId()).toList());
        Assert.assertFalse(result.getLlmObservation().contains("private/research-1"));
    }

    @Test
    public void shouldRejectAutomaticReportWithoutCurrentResearchArtifact() {
        Fixture fixture = new Fixture();
        ReportStreamClient client = (service, endpoint, request, eventConsumer, deltaConsumer) -> {
            Assert.fail("没有研究材料时不得调用 Python Report 服务");
            return null;
        };

        ToolExecutionResult result = reportTool(fixture, client).executeRich(automaticRequest(), fixture.context);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("report_input_missing", result.getErrorCode());
        Assert.assertEquals("failed", fixture.tools.findByRunId("run-1").get(0).getStatus());
        Assert.assertTrue(fixture.executions.findByToolInvocationId(
                fixture.tools.findByRunId("run-1").get(0).getToolInvocationId()
        ).isEmpty());
    }

    @Test
    public void shouldRegisterArtifactPersistStableFactsAndKeepLocatorsOutOfToolResult() {
        Fixture fixture = new Fixture();
        fixture.artifacts.save(inputArtifact("input-1", "run-1", "session-1", "text/markdown", "source.md", 128L));

        ReportStreamClient client = (service, endpoint, request, eventConsumer, deltaConsumer) -> {
            eventConsumer.accept(event(request.getExecutionId(), 1, "inputs_resolved", "running", "inputs", Map.of(
                    "inputArtifactCount", 1,
                    "acceptedArtifactCount", 1
            )));
            deltaConsumer.accept(delta(request.getExecutionId()));
            eventConsumer.accept(event(request.getExecutionId(), 2, "generation_started", "running", "generation", Map.of(
                    "outputType", "markdown"
            )));
            eventConsumer.accept(event(request.getExecutionId(), 3, "artifact_created", "running", "artifact", Map.of(
                    "outputType", "markdown",
                    "fileName", "report.md",
                    "artifact", outputArtifact()
            )));
            eventConsumer.accept(event(request.getExecutionId(), 4, "completed", "completed", "报告交付完成", Map.of(
                    "outputType", "markdown"
            )));
            return finalResult(request.getExecutionId());
        };
        ReportTool tool = reportTool(fixture, client);

        ToolExecutionResult result = tool.executeRich(request(), fixture.context);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(List.of("artifact-out"), result.getArtifactRefs());
        Assert.assertTrue(result.getLlmObservation().contains("Artifact ID：artifact-out"));
        Assert.assertFalse(result.getLlmObservation().contains("private/output"));
        Assert.assertFalse(result.getStructuredOutput().toString().contains("private/output"));
        Assert.assertEquals(4, fixture.events.findByToolInvocationIdOrderBySequenceNo(
                fixture.tools.findByRunId("run-1").get(0).getToolInvocationId()).size());
        Assert.assertEquals(1, fixture.artifacts.findByToolInvocationId(
                fixture.tools.findByRunId("run-1").get(0).getToolInvocationId()).size());
        Assert.assertEquals("artifact-out", fixture.executions.findByToolInvocationId(
                fixture.tools.findByRunId("run-1").get(0).getToolInvocationId()).orElseThrow().getOutputArtifactId());
        String responsePayload = fixture.tools.findByRunId("run-1").get(0).getResponsePayloadJson();
        Assert.assertFalse(responsePayload.contains("private/output"));
        Assert.assertFalse(responsePayload.contains("storageKey"));
        // Tool 开始、四条稳定阶段、正式 Artifact、正文 delta、最终 Tool Result 各一条。
        Assert.assertEquals(8, fixture.published.size());
        Assert.assertEquals(RuntimeEventType.REPORT_CONTENT_DELTA, fixture.published.get(2).getEventType());
        Assert.assertEquals(
                "report.content_delta.v1",
                ((ReportContentDeltaPayload) fixture.published.get(2).getPayload()).getSchemaVersion()
        );
        Assert.assertNull(fixture.published.get(2).factKey());
    }

    private static Stream<String> sseLines(String... frames) {
        return java.util.Arrays.stream(frames)
                .flatMap(frame -> java.util.Arrays.stream((frame + "\n").split("\\n", -1)));
    }

    private static ReportStartRequest startRequest(List<String> artifactIds) {
        ToolInvokeRequest request = ToolInvokeRequest.builder().arguments(Map.of(
                "task", "整理报告",
                "inputArtifactIds", artifactIds,
                "outputType", "markdown"
        )).build();
        return ReportStartRequest.from(request, "execution-1");
    }

    private static AgentContext context() {
        AgentContext context = AgentContext.builder()
                .sessionId("session-1")
                .requestId("request-1")
                .traceId("trace-1")
                .currentStep(2)
                .build();
        context.setAttribute("runId", "run-1");
        context.setAttribute("agentId", "agent-1");
        return context;
    }

    private static ToolInvokeRequest request() {
        return ToolInvokeRequest.builder()
                .toolName("report_tool")
                .toolCallId("call-1")
                .requestId("request-1")
                .sessionId("session-1")
                .step(2)
                .arguments(Map.of(
                        "task", "根据材料整理项目调研报告",
                        "inputArtifactIds", List.of("input-1"),
                        "outputFileName", "report",
                        "outputType", "markdown"
                ))
                .build();
    }

    private static ToolInvokeRequest automaticRequest() {
        return ToolInvokeRequest.builder()
                .toolName("report_tool")
                .toolCallId("call-1")
                .requestId("request-1")
                .sessionId("session-1")
                .step(2)
                .arguments(Map.of(
                        "task", "根据本次调研材料生成报告",
                        "outputFileName", "report",
                        "outputType", "markdown"
                ))
                .build();
    }

    private static ReportTool reportTool(Fixture fixture, ReportStreamClient client) {
        return new ReportTool(
                "report_tool",
                "report",
                Map.of(),
                "http://unused",
                "/unused",
                client,
                new ReportInputArtifactCandidateResolver(),
                new ReportInputArtifactResolver(fixture.artifacts),
                fixture.ledgerFactService,
                fixture.runtimeFactService,
                fixture.runtimeEventPublisher
        );
    }

    private static void registerResearchArtifact(
            AgentContext context,
            String artifactId,
            String runId,
            String toolName,
            String artifactType,
            String artifactRole,
            String visibility
    ) {
        context.requireArtifactRuntimeRegistry().register(
                ArtifactRuntimeSource.builder()
                        .runId(runId)
                        .sessionId(context.getSessionId())
                        .requestId(context.getRequestId())
                        .toolCallId("call-" + artifactId)
                        .toolName(toolName)
                        .build(),
                ArtifactRuntimeDescriptor.builder()
                        .artifactId(artifactId)
                        .artifactType(artifactType)
                        .artifactRole(artifactRole)
                        .visibility(visibility)
                        .storageType("local_artifact_storage")
                        .storageKey("private/" + artifactId)
                        .build()
        );
    }

    private static ReportStreamEvent event(
            String executionId,
            int sequence,
            String phase,
            String status,
            String summary,
            Map<String, Object> data
    ) {
        ReportStreamEvent event = new ReportStreamEvent();
        event.setSchemaVersion("report_progress_event.v1");
        event.setEventId("evt-" + sequence);
        event.setExecutionId(executionId);
        event.setSequence(sequence);
        event.setPhase(phase);
        event.setStatus(status);
        event.setOccurredAt(Instant.parse("2026-08-28T10:00:0" + sequence + "Z"));
        event.setSummary(summary);
        event.setData(new LinkedHashMap<>(data));
        return event;
    }

    private static ReportContentDelta delta(String executionId) {
        ReportContentDelta delta = new ReportContentDelta();
        delta.setSchemaVersion("report_content_delta.v1");
        delta.setExecutionId(executionId);
        delta.setOutputType("markdown");
        delta.setChunkIndex(1);
        delta.setContent("临时草稿内容");
        return delta;
    }

    private static ReportFinalResult finalResult(String executionId) {
        ReportFinalResult result = new ReportFinalResult();
        result.setSchemaVersion("report_result.v1");
        result.setExecutionId(executionId);
        result.setReportExecutionId("python-report-1");
        result.setStatus("completed");
        result.setSummary("报告交付完成");
        result.setOutputType("markdown");
        result.setLimitations(List.of("材料按上下文预算读取。"));
        result.setMaterialStatistics(Map.of("inputArtifactCount", 1));
        result.setArtifact(outputArtifact());
        return result;
    }

    private static Map<String, Object> outputArtifact() {
        return Map.ofEntries(
                Map.entry("schemaVersion", "report_artifact.v1"),
                Map.entry("artifactId", "artifact-out"),
                Map.entry("name", "report.md"),
                Map.entry("artifactType", "report"),
                Map.entry("artifactRole", "output"),
                Map.entry("visibility", "visible"),
                Map.entry("sourceType", "python_artifact"),
                Map.entry("sourceName", "report_tool"),
                Map.entry("fileName", "report.md"),
                Map.entry("storageType", "local_artifact_storage"),
                Map.entry("storageKey", "private/output/report.md"),
                Map.entry("storageRef", "private/output/report.md"),
                Map.entry("contentSummary", "报告交付完成"),
                Map.entry("mimeType", "text/markdown"),
                Map.entry("fileSize", 128),
                Map.entry("fileHash", "sha256:test"),
                Map.entry("metadata", Map.of("preview", Map.of("kind", "markdown", "schemaVersion", "v1")))
        );
    }

    private static ArtifactLedgerEntity inputArtifact(
            String artifactId,
            String runId,
            String sessionId,
            String mimeType,
            String fileName,
            long fileSize
    ) {
        return ArtifactLedgerEntity.builder()
                .artifactId(artifactId)
                .runId(runId)
                .sessionId(sessionId)
                .artifactRole("output")
                .visibility("visible")
                .artifactType("report")
                .artifactName(fileName)
                .fileName(fileName)
                .mimeType(mimeType)
                .fileSize(fileSize)
                .size(fileSize)
                .storageType("local_artifact_storage")
                .storageKey("private/" + artifactId)
                .storageRef("private/" + artifactId)
                .deleted(0)
                .createdAt(Instant.now())
                .build();
    }

    private static class Fixture {
        final InMemoryArtifactLedgerRepository artifacts = new InMemoryArtifactLedgerRepository();
        final InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        final InMemoryRuntimeEventLedgerRepository events = new InMemoryRuntimeEventLedgerRepository();
        final InMemoryReportExecutionRepository executions = new InMemoryReportExecutionRepository();
        final List<RuntimeEventDraft> published = new ArrayList<>();
        final RuntimeEventPublisher runtimeEventPublisher = published::add;
        final LedgerRecordingService recordingService = new LedgerRecordingService(
                new InMemorySessionLedgerRepository(),
                new InMemoryRunLedgerRepository(),
                new InMemoryLlmInvocationLedgerRepository(),
                tools,
                artifacts
        );
        final LedgerFactService ledgerFactService = new LedgerFactService(
                recordingService,
                null,
                new RuntimeEventMapper(),
                published::add
        );
        final ArtifactRuntimeRegistrationService artifactRegistrationService = new ArtifactRuntimeRegistrationService(
                new ArtifactRuntimeLedgerRecorder(recordingService, new RuntimeEventMapper(), published::add),
                artifacts,
                new RuntimeEventMapper(),
                published::add
        );
        final ReportRuntimeFactService runtimeFactService = new ReportRuntimeFactService(
                executions,
                artifactRegistrationService,
                new RichRuntimeProgressRecordingService(events, published::add)
        );
        final AgentContext context = context();
    }
}
