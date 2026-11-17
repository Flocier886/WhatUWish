package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeLedgerRecorder;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistrationService;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryImageGenerationExecutionRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
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

/** Image 私有协议、输入授权、Artifact 事实和 ReAct observation 的定向回归。 */
public class ImageGenerationRichToolSupportTest {

    @Test
    public void shouldConsumeOrderedSseAndRejectInconsistentTerminal() {
        HttpImageGenerationStreamClient client = new HttpImageGenerationStreamClient(HttpClient.newHttpClient());
        String inputs = frame("progress", eventJson("evt-1", 1, "inputs_resolved", "running", Map.of()));
        String started = frame("progress", eventJson("evt-2", 2, "generation_started", "running", Map.of("mode", "images")));
        String artifact = frame("artifact", eventJson("evt-3", 3, "artifact_created", "running", Map.of("artifact", outputArtifact())));
        String completed = frame("progress", eventJson("evt-4", 4, "completed", "completed", Map.of("generatedCount", 1)));
        String result = frame("result", resultJson("completed"));

        List<ImageGenerationStreamEvent> events = new ArrayList<>();
        ImageGenerationFinalResult finalResult = client.consume(lines(inputs, started, artifact, completed, result), "execution-1", events::add);

        Assert.assertEquals("completed", finalResult.getStatus());
        Assert.assertEquals(4, events.size());

        assertProtocolError(() -> client.consume(
                lines(frame("progress", eventJson("evt-1", 2, "inputs_resolved", "running", Map.of()))),
                "execution-1", ignored -> { }
        ));
        assertProtocolError(() -> client.consume(lines(inputs, frame("result", resultJson("completed"))), "execution-1", ignored -> { }));
        assertProtocolError(() -> client.consume(
                lines(inputs, started, artifact, completed, frame("result", resultJson("failed"))), "execution-1", ignored -> { }
        ));
        assertProtocolError(() -> client.consume(
                lines(frame("progress", eventJson("evt-1", 1, "completed", "running", Map.of()))), "execution-1", ignored -> { }
        ));
        ImageGenerationFinalResult incompleteDescriptor = finalResult("execution-1", "completed");
        incompleteDescriptor.setArtifacts(List.of(Map.of("schemaVersion", "image_generation_artifact.v1", "artifactId", "image-out")));
        assertProtocolError(() -> client.consume(
                lines(inputs, started, artifact, completed, frame("result", com.alibaba.fastjson.JSON.toJSONString(incompleteDescriptor))),
                "execution-1", ignored -> { }
        ));
        assertProtocolError(() -> client.consume(
                lines(inputs, started, artifact, completed, result, frame("progress", eventJson("evt-5", 5, "completed", "completed", Map.of()))),
                "execution-1", ignored -> { }
        ));
    }

    @Test
    public void shouldValidateStartRequestAndResolveOnlyCurrentRunRasterArtifacts() {
        assertRequestError(Map.of("prompt", "draw", "mode", "edits"));
        assertRequestError(Map.of("prompt", "draw", "n", 11));
        assertRequestError(Map.of("prompt", "draw", "referenceArtifactIds", List.of("image-1", "image-1")));

        InMemoryArtifactLedgerRepository artifacts = new InMemoryArtifactLedgerRepository();
        artifacts.save(inputArtifact("image-1", "run-1", "session-1", "image/png", 256L));
        ImageGenerationInputArtifactResolver resolver = new ImageGenerationInputArtifactResolver(artifacts);
        ImageGenerationStartRequest request = startRequest(Map.of("prompt", "edit", "mode", "edits", "referenceArtifactIds", List.of("image-1")));
        ImageGenerationInputArtifactResolver.ResolvedInputs resolved = resolver.resolve(context(), request);
        Assert.assertEquals("image-1", resolved.references().get(0).getArtifactId());
        Assert.assertEquals("private/image-1", resolved.references().get(0).getStorageKey());

        artifacts.save(inputArtifact("svg-1", "run-1", "session-1", "image/svg+xml", 256L));
        assertResolverError(resolver, startRequest(Map.of("prompt", "edit", "mode", "edits", "referenceArtifactIds", List.of("svg-1"))), "image_generation_input_unsupported");
        artifacts.save(inputArtifact("other-run", "run-2", "session-1", "image/png", 256L));
        assertResolverError(resolver, startRequest(Map.of("prompt", "edit", "mode", "edits", "referenceArtifactIds", List.of("other-run"))), "image_generation_input_forbidden");

        ArtifactLedgerEntity workbenchInput = inputArtifact("workbench-input", "run-1", "session-1", "image/png", 256L);
        workbenchInput.setArtifactRole("input");
        workbenchInput.setVisibility("internal");
        workbenchInput.setSourceType("user_upload");
        workbenchInput.setSourceName("image_generation_workbench");
        artifacts.save(workbenchInput);
        Assert.assertEquals("workbench-input", resolver.resolve(context(), startRequest(Map.of(
                "prompt", "edit", "mode", "edits", "referenceArtifactIds", List.of("workbench-input")
        ))).references().get(0).getArtifactId());

        ArtifactLedgerEntity arbitraryInternal = inputArtifact("arbitrary-internal", "run-1", "session-1", "image/png", 256L);
        arbitraryInternal.setArtifactRole("input");
        arbitraryInternal.setVisibility("internal");
        arbitraryInternal.setSourceType("tool_output");
        arbitraryInternal.setSourceName("another_tool");
        artifacts.save(arbitraryInternal);
        assertResolverError(resolver, startRequest(Map.of("prompt", "edit", "mode", "edits",
                "referenceArtifactIds", List.of("arbitrary-internal"))), "image_generation_input_forbidden");
    }

    @Test
    public void shouldMapHttpFailuresToControlledErrorsWithoutRawResponse() {
        HttpImageGenerationStreamClient client = new HttpImageGenerationStreamClient(HttpClient.newHttpClient());

        ImageGenerationStreamException invalid = client.parseHttpError(422,
                "{\"errorCode\":\"image_generation_request_invalid\",\"detail\":\"private/storage/key\"}");
        ImageGenerationStreamException unavailable = client.parseHttpError(500, "upstream raw response private/storage/key");

        Assert.assertEquals("image_generation_request_invalid", invalid.getErrorCode());
        Assert.assertFalse(invalid.getMessage().contains("private/storage/key"));
        Assert.assertEquals("image_generation_http_error", unavailable.getErrorCode());
        Assert.assertFalse(unavailable.getMessage().contains("private/storage/key"));
    }

    @Test
    public void shouldRegisterEachOutputAndKeepPrivateFieldsOutOfObservationAndFacts() {
        Fixture fixture = new Fixture();
        fixture.artifacts.save(inputArtifact("input-1", "run-1", "session-1", "image/png", 256L));
        ImageGenerationStreamClient client = (service, endpoint, request, consumer) -> {
            consumer.accept(event(request.getExecutionId(), 1, "inputs_resolved", "running", Map.of("mode", "edits", "referenceCount", 1)));
            consumer.accept(event(request.getExecutionId(), 2, "generation_started", "running", Map.of("prompt", "must-not-persist", "size", "1024x1024")));
            consumer.accept(event(request.getExecutionId(), 3, "artifact_created", "running", Map.of("artifact", outputArtifact())));
            consumer.accept(event(request.getExecutionId(), 4, "completed", "completed", Map.of("generatedCount", 1, "storageKey", "private/not-visible")));
            return finalResult(request.getExecutionId(), "completed");
        };
        ImageGenerationTool tool = tool(fixture, client);

        ToolExecutionResult result = tool.executeRich(toolRequest(), fixture.context);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(List.of("image-out"), result.getArtifactRefs());
        Assert.assertTrue(result.getLlmObservation().contains("Artifact ID：image-out"));
        Assert.assertFalse(result.getLlmObservation().contains("private/output"));
        Assert.assertFalse(result.getStructuredOutput().toString().contains("private/output"));
        String invocationId = fixture.tools.findByRunId("run-1").get(0).getToolInvocationId();
        Assert.assertEquals(4, fixture.events.findByToolInvocationIdOrderBySequenceNo(invocationId).size());
        Assert.assertEquals(1, fixture.artifacts.findByToolInvocationId(invocationId).size());
        Assert.assertEquals(1, fixture.executions.findByToolInvocationId(invocationId).orElseThrow().getGeneratedOutputCount().intValue());
        String facts = fixture.events.findByToolInvocationIdOrderBySequenceNo(invocationId).toString();
        Assert.assertFalse(facts.contains("must-not-persist"));
        Assert.assertFalse(facts.contains("private/output"));
        Assert.assertFalse(facts.contains("private/not-visible"));
    }

    @Test
    public void shouldReturnControlledFailureObservationWithoutClaimingArtifactDelivery() {
        Fixture fixture = new Fixture();
        ImageGenerationTool tool = tool(fixture, (service, endpoint, request, consumer) -> {
            throw new ImageGenerationStreamException("image_generation_transport_error", "上游图片服务暂不可用");
        });

        ToolExecutionResult result = tool.executeRich(ToolInvokeRequest.builder()
                .toolName("image_generation_tool").toolCallId("call-1").requestId("request-1").sessionId("session-1")
                .step(2).arguments(Map.of("prompt", "生成一张城市夜景")).build(), fixture.context);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("image_generation_transport_error", result.getErrorCode());
        Assert.assertTrue(result.getLlmObservation().contains("不要假设图片已经产出"));
        Assert.assertTrue(result.getArtifactRefs().isEmpty());
    }

    private static ImageGenerationTool tool(Fixture fixture, ImageGenerationStreamClient client) {
        return new ImageGenerationTool("image_generation_tool", "image", Map.of(), "http://unused", "/unused",
                new DefaultImageGenerationExecutionKernel(client),
                new ImageGenerationInputArtifactResolver(fixture.artifacts), fixture.ledgerFactService, fixture.runtimeFactService);
    }

    private static ToolInvokeRequest toolRequest() {
        return ToolInvokeRequest.builder().toolName("image_generation_tool").toolCallId("call-1").requestId("request-1")
                .sessionId("session-1").step(2).arguments(Map.of("prompt", "将参考图改为雨夜城市", "mode", "edits",
                        "referenceArtifactIds", List.of("input-1"), "size", "1024x1024", "n", 1)).build();
    }

    private static ImageGenerationStartRequest startRequest(Map<String, Object> arguments) {
        return ImageGenerationStartRequest.from(ToolInvokeRequest.builder().arguments(arguments).build(), "execution-1");
    }

    private static void assertRequestError(Map<String, Object> arguments) {
        try {
            startRequest(arguments);
            Assert.fail("请求必须被拒绝");
        } catch (ImageGenerationStreamException exception) {
            Assert.assertEquals("image_generation_request_invalid", exception.getErrorCode());
        }
    }

    private static void assertResolverError(ImageGenerationInputArtifactResolver resolver, ImageGenerationStartRequest request, String errorCode) {
        try {
            resolver.resolve(context(), request);
            Assert.fail("不符合授权边界的图片输入必须被拒绝");
        } catch (ImageGenerationStreamException exception) {
            Assert.assertEquals(errorCode, exception.getErrorCode());
        }
    }

    private static void assertProtocolError(Runnable action) {
        try {
            action.run();
            Assert.fail("不可信 SSE 协议必须被拒绝");
        } catch (ImageGenerationStreamException exception) {
            Assert.assertEquals("image_generation_protocol_error", exception.getErrorCode());
        }
    }

    private static AgentContext context() {
        AgentContext context = AgentContext.builder().sessionId("session-1").requestId("request-1").traceId("trace-1").currentStep(2).build();
        context.setAttribute("runId", "run-1");
        context.setAttribute("agentId", "agent-1");
        return context;
    }

    private static ArtifactLedgerEntity inputArtifact(String artifactId, String runId, String sessionId, String mimeType, long fileSize) {
        return ArtifactLedgerEntity.builder().artifactId(artifactId).runId(runId).sessionId(sessionId).artifactRole("output")
                .visibility("visible").artifactType("image").artifactName(artifactId + ".png").fileName(artifactId + ".png")
                .mimeType(mimeType).fileSize(fileSize).size(fileSize).storageType("local_artifact_storage")
                .storageKey("private/" + artifactId).storageRef("private/" + artifactId).deleted(0).createdAt(Instant.now()).build();
    }

    private static ImageGenerationStreamEvent event(String executionId, int sequence, String phase, String status, Map<String, Object> data) {
        ImageGenerationStreamEvent event = new ImageGenerationStreamEvent();
        event.setSchemaVersion("image_generation_progress_event.v1");
        event.setEventId("evt-" + sequence); event.setExecutionId(executionId); event.setSequence(sequence);
        event.setPhase(phase); event.setStatus(status); event.setOccurredAt(Instant.parse("2026-08-29T10:00:00Z"));
        event.setSummary("图片生成阶段 " + phase); event.setData(new LinkedHashMap<>(data));
        return event;
    }

    private static Map<String, Object> outputArtifact() {
        return Map.ofEntries(
                Map.entry("schemaVersion", "image_generation_artifact.v1"), Map.entry("artifactId", "image-out"),
                Map.entry("name", "image-out.png"), Map.entry("artifactType", "image"), Map.entry("artifactRole", "output"),
                Map.entry("visibility", "visible"), Map.entry("sourceType", "python_artifact"), Map.entry("sourceName", "image_generation_tool"),
                Map.entry("fileName", "image-out.png"), Map.entry("storageType", "local_artifact_storage"),
                Map.entry("storageKey", "private/output/image-out.png"), Map.entry("storageRef", "private/output/image-out.png"),
                Map.entry("contentSummary", "已生成图片"), Map.entry("mimeType", "image/png"), Map.entry("fileSize", 128),
                Map.entry("fileHash", "sha256:test"), Map.entry("metadata", Map.of("preview", Map.of("kind", "image", "schemaVersion", "v1")))
        );
    }

    private static ImageGenerationFinalResult finalResult(String executionId, String status) {
        ImageGenerationFinalResult result = new ImageGenerationFinalResult();
        result.setSchemaVersion("image_generation_result.v1"); result.setExecutionId(executionId); result.setImageExecutionId("python-image-1");
        result.setStatus(status); result.setSummary("图片生成完成"); result.setMode("images"); result.setSize("1024x1024");
        result.setRequestedCount(1); result.setUsedFallback(false); result.setArtifacts("completed".equals(status) ? List.of(outputArtifact()) : List.of());
        if (!"completed".equals(status)) result.setErrorCode("image_generation_failed");
        return result;
    }

    private static String frame(String type, String data) { return "event: " + type + "\n" + "data: " + data + "\n"; }
    private static Stream<String> lines(String... frames) {
        return java.util.Arrays.stream(frames).flatMap(frame -> java.util.Arrays.stream((frame + "\n").split("\\n", -1)));
    }
    private static String eventJson(String eventId, int sequence, String phase, String status, Map<String, Object> data) {
        return com.alibaba.fastjson.JSON.toJSONString(Map.of("schemaVersion", "image_generation_progress_event.v1", "eventId", eventId,
                "executionId", "execution-1", "sequence", sequence, "phase", phase, "status", status,
                "occurredAt", "2026-08-29T10:00:00Z", "summary", "stage", "data", data));
    }
    private static String resultJson(String status) { return com.alibaba.fastjson.JSON.toJSONString(finalResult("execution-1", status)); }

    private static class Fixture {
        final InMemoryArtifactLedgerRepository artifacts = new InMemoryArtifactLedgerRepository();
        final InMemoryToolInvocationLedgerRepository tools = new InMemoryToolInvocationLedgerRepository();
        final InMemoryRuntimeEventLedgerRepository events = new InMemoryRuntimeEventLedgerRepository();
        final InMemoryImageGenerationExecutionRepository executions = new InMemoryImageGenerationExecutionRepository();
        final List<RuntimeEventDraft> published = new ArrayList<>();
        final LedgerRecordingService recordingService = new LedgerRecordingService(new InMemorySessionLedgerRepository(), new InMemoryRunLedgerRepository(),
                new InMemoryLlmInvocationLedgerRepository(), tools, artifacts);
        final LedgerFactService ledgerFactService = new LedgerFactService(recordingService, null, new RuntimeEventMapper(), published::add);
        final ArtifactRuntimeRegistrationService artifactRegistrationService = new ArtifactRuntimeRegistrationService(
                new ArtifactRuntimeLedgerRecorder(recordingService, new RuntimeEventMapper(), published::add), artifacts, new RuntimeEventMapper(), published::add);
        final ImageGenerationRuntimeFactService runtimeFactService = new ImageGenerationRuntimeFactService(executions, artifactRegistrationService,
                new RichRuntimeProgressRecordingService(events, published::add));
        final AgentContext context = context();
    }
}
