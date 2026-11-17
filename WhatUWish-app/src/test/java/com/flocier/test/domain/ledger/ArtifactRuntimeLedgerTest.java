package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.DefaultToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeCollector;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeLedgerRecorder;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeFormatter;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.LedgerQueryService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryLlmInvocationLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryRunLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemorySessionLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryToolInvocationLedgerRepository;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ArtifactRuntimeLedgerTest {

    @Test
    public void shouldCollectPythonArtifactsAndSkillGeneratedArtifacts() {
        ArtifactRuntimeCollector collector = new ArtifactRuntimeCollector();
        ToolInvokeRequest request = request("artifact_save", "call-artifact-save");
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("artifact_save")
                .toolCallId("call-artifact-save")
                .sourceType("python")
                .artifactDescriptors(List.of(completeDescriptor(
                        "artifact-report", "report.md", "artifact_save", "D:/tmp/report.md", 128
                )))
                .structuredOutput(Map.of("generatedArtifacts", List.of(completeDescriptor(
                        "artifact-summary", "summary.md", "skill_generated_artifact", "D:/tmp/summary.md", 256
                ))))
                .build();

        List<ArtifactRuntimeDescriptor> descriptors = collector.collect(request, result, context());

        Assert.assertEquals(2, descriptors.size());
        Assert.assertTrue(descriptors.stream().anyMatch(item ->
                "artifact_save".equals(item.getSourceType())
                        && "report.md".equals(item.getFileName())
                        && Long.valueOf(128L).equals(item.getFileSize())));
        Assert.assertTrue(descriptors.stream().anyMatch(item ->
                "skill_generated_artifact".equals(item.getSourceType())
                        && "summary.md".equals(item.getFileName())
                        && Long.valueOf(256L).equals(item.getFileSize())));
    }

    @Test
    public void shouldNotCollectPlainStructuredOutputOrArtifactReadResult() {
        ArtifactRuntimeCollector collector = new ArtifactRuntimeCollector();
        ToolExecutionResult plainResult = ToolExecutionResult.builder()
                .toolName("file_read")
                .toolCallId("call-plain")
                .sourceType("python")
                .llmObservation("已读取文件内容")
                .structuredOutput(Map.of(
                        "path", "README.md",
                        "content", "这只是读取内容，不是新产物"
                ))
                .build();
        ToolExecutionResult artifactReadResult = ToolExecutionResult.builder()
                .toolName("artifact_read")
                .toolCallId("call-artifact-read")
                .sourceType("python")
                .structuredOutput(Map.of(
                        "path", "D:/tmp/report.md",
                        "content", "读取到的产物正文"
                ))
                .artifactDescriptors(List.of())
                .build();

        Assert.assertTrue(collector.collect(request("file_read", "call-plain"), plainResult, context()).isEmpty());
        Assert.assertTrue(collector.collect(request("artifact_read", "call-artifact-read"), artifactReadResult, context()).isEmpty());
    }

    @Test
    public void shouldRecordRuntimeArtifactsWithToolOwnershipAndIdempotency() {
        Fixture fixture = new Fixture();
        ToolInvocationLedgerEntity toolInvocation = fixture.recordingService.recordToolCall(
                fixture.context,
                request("artifact_save", "call-record"),
                ToolExecutionResult.builder()
                        .toolName("artifact_save")
                        .toolCallId("call-record")
                        .sourceType("python")
                        .status("success")
                        .success(true)
                        .finishedAt(Instant.now())
                        .durationMs(12L)
                        .build()
        );
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("artifact_save")
                .toolCallId("call-record")
                .sourceType("python")
                .artifactDescriptors(List.of(completeDescriptor(
                        "artifact-ledger-report", "ledger-report.md", "artifact_save", "D:/tmp/ledger-report.md", 64
                )))
                .build();

        fixture.register(request("artifact_save", "call-record"), result);
        fixture.artifactRecorder.recordToolArtifacts(fixture.context, request("artifact_save", "call-record"), result, toolInvocation);
        fixture.artifactRecorder.recordToolArtifacts(fixture.context, request("artifact_save", "call-record"), result, toolInvocation);

        List<ArtifactLedgerEntity> artifacts = fixture.artifactRepository.findByRunIdAndToolCallId("run-runtime", "call-record");
        Assert.assertEquals(1, artifacts.size());
        ArtifactLedgerEntity artifact = artifacts.get(0);
        Assert.assertEquals(toolInvocation.getToolInvocationId(), artifact.getToolInvocationId());
        Assert.assertEquals("call-record", artifact.getToolCallId());
        Assert.assertEquals("artifact_save", artifact.getSourceType());
        Assert.assertEquals("artifact_save", artifact.getSourceName());
        Assert.assertEquals("ledger-report.md", artifact.getFileName());
        Assert.assertEquals("D:/tmp/ledger-report.md", artifact.getStorageRef());
        Assert.assertEquals(Long.valueOf(64L), artifact.getFileSize());
    }

    @Test
    public void shouldKeepExplicitArtifactWhenToolFinishesWithFailure() {
        Fixture fixture = new Fixture();
        ToolInvokeRequest request = request("file_write", "call-failed-after-save");
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("file_write")
                .toolCallId("call-failed-after-save")
                .sourceType("python")
                .success(false)
                .status("failed")
                .errorCode("post_save_follow_up_failed")
                .artifactDescriptors(List.of(completeDescriptor(
                        "artifact-survives-failure",
                        "saved-before-failure.md",
                        "file_write",
                        "artifacts/saved-before-failure.md",
                        64
                )))
                .build();
        ToolInvocationLedgerEntity invocation = fixture.recordingService.recordToolCall(
                fixture.context,
                request,
                result
        );

        fixture.register(request, result);
        fixture.artifactRecorder.recordToolArtifacts(fixture.context, request, result, invocation);

        Assert.assertEquals(1, fixture.artifactRepository
                .findByRunIdAndToolCallId("run-runtime", "call-failed-after-save")
                .size());
    }

    @Test
    public void shouldUseBoundedIdempotencyKeyWhenArtifactStorageKeyIsLong() {
        Fixture fixture = new Fixture();
        ToolInvokeRequest request = request("artifact_save", "call-long-storage-key");
        ToolInvocationLedgerEntity toolInvocation = fixture.recordingService.recordToolCall(
                fixture.context,
                request,
                ToolExecutionResult.builder()
                        .toolName("artifact_save")
                        .toolCallId("call-long-storage-key")
                        .sourceType("python")
                        .status("success")
                        .success(true)
                        .finishedAt(Instant.now())
                        .build()
        );
        String storageKey = "session/run/" + "x".repeat(400) + ".txt";
        fixture.context.requireArtifactRuntimeRegistry().register(
                ArtifactRuntimeSource.from(fixture.context, request, "artifact_save"),
                ArtifactRuntimeDescriptor.builder()
                        .artifactId("artifact-long-storage-key")
                        .name("report.txt")
                        .fileName("report.txt")
                        .artifactType("text")
                        .artifactRole("output")
                        .visibility("visible")
                        .sourceType("artifact_save")
                        .sourceName("artifact_save")
                        .storageType("local_artifact_storage")
                        .storageRef(storageKey)
                        .storageKey(storageKey)
                        .build()
        );

        fixture.artifactRecorder.recordToolArtifacts(fixture.context, request,
                ToolExecutionResult.builder().toolName("artifact_save").toolCallId("call-long-storage-key").build(), toolInvocation);
        fixture.artifactRecorder.recordToolArtifacts(fixture.context, request,
                ToolExecutionResult.builder().toolName("artifact_save").toolCallId("call-long-storage-key").build(), toolInvocation);

        List<ArtifactLedgerEntity> artifacts = fixture.artifactRepository
                .findByRunIdAndToolCallId("run-runtime", "call-long-storage-key");
        Assert.assertEquals(1, artifacts.size());
        Assert.assertEquals(storageKey, artifacts.get(0).getStorageKey());
        Assert.assertTrue(artifacts.get(0).getIdempotencyKey().startsWith("artifact:"));
        Assert.assertEquals(57, artifacts.get(0).getIdempotencyKey().length());
    }

    @Test
    public void shouldRecordArtifactsAfterDefaultToolRunnerRecordsToolInvocation() throws Exception {
        Fixture fixture = new Fixture();
        DefaultToolRunner runner = new DefaultToolRunner();
        setField(runner, "ledgerFactService", fixture.ledgerFactService);
        setField(runner, "artifactRuntimeLedgerRecorder", fixture.artifactRecorder);
        setField(runner, "artifactRuntimeCollector", fixture.artifactCollector);
        setField(runner, "artifactRuntimeFormatter", fixture.artifactFormatter);

        ToolExecutionResult result = runner.run(
                new ToolCollection(List.of(new ArtifactProducingTool())),
                request("artifact_save", "call-runner"),
                fixture.context
        );

        Assert.assertTrue(result.isSuccess());
        List<ArtifactLedgerEntity> artifacts = fixture.artifactRepository.findByRunIdAndToolCallId("run-runtime", "call-runner");
        Assert.assertEquals(1, artifacts.size());
        Assert.assertEquals("runner-report.md", artifacts.get(0).getFileName());
        Assert.assertEquals("call-runner", artifacts.get(0).getToolCallId());
        Assert.assertNotNull(artifacts.get(0).getToolInvocationId());
        Assert.assertTrue(result.getLlmObservation().contains("artifactKey:"));
        Assert.assertTrue(fixture.toolRepository.findByRunId("run-runtime").get(0).getResponsePayloadJson()
                .contains("artifactKey:"));
        Assert.assertEquals(1, fixture.toolRepository.findByRunId("run-runtime").size());
    }

    /**
     * 受控跨层验收：工具正文只能作为本次模型 observation 或 artifact 内容存在，
     * 不得反向写入通用 Tool Invocation 的 canonical result。
     */
    @Test
    public void shouldKeepWebFetchAndArtifactReadBodiesOutsideV2ToolLedger() throws Exception {
        Fixture fixture = new Fixture();
        DefaultToolRunner runner = newRunner(fixture);
        ToolCollection tools = new ToolCollection(List.of(new WebFetchArtifactTool(), new ArtifactReadPreviewTool()));

        ToolExecutionResult fetchResult = runner.run(tools, request("web_fetch", "call-web-fetch"), fixture.context);
        ToolExecutionResult readResult = runner.run(tools, request("artifact_read", "call-artifact-read"), fixture.context);

        ToolInvocationLedgerEntity fetchInvocation = findInvocation(fixture, "call-web-fetch");
        ToolInvocationLedgerEntity readInvocation = findInvocation(fixture, "call-artifact-read");

        Assert.assertTrue(fetchResult.getLlmObservation().contains("artifactId=artifact-web-page"));
        Assert.assertTrue(fetchResult.getLlmObservation().contains("artifactKey:"));
        Assert.assertTrue(readResult.getLlmObservation().contains("nextOffset=120"));
        Assert.assertFalse(readResult.getLlmObservation().contains("WEB_BODY_MUST_NOT_ENTER_TOOL_LEDGER"));

        assertV2PayloadExcludesLegacyCopies(fetchInvocation.getResponsePayloadJson());
        assertV2PayloadExcludesLegacyCopies(readInvocation.getResponsePayloadJson());
        Assert.assertFalse(fetchInvocation.getResponsePayloadJson().contains("WEB_BODY_MUST_NOT_ENTER_TOOL_LEDGER"));
        Assert.assertFalse(readInvocation.getResponsePayloadJson().contains("WEB_BODY_MUST_NOT_ENTER_TOOL_LEDGER"));
        Assert.assertTrue(fetchInvocation.getResponsePayloadJson().contains("artifact-web-page"));
        Assert.assertTrue(readInvocation.getResponsePayloadJson().contains("\"hasMore\":true"));
        Assert.assertTrue(readInvocation.getResponsePayloadJson().contains("\"nextOffset\":120"));

        List<ArtifactLedgerEntity> artifacts = fixture.artifactRepository
                .findByRunIdAndToolCallId("run-runtime", "call-web-fetch");
        Assert.assertEquals(1, artifacts.size());
        Assert.assertEquals("artifact-web-page", artifacts.get(0).getArtifactId());
        Assert.assertEquals("artifacts/run-runtime/web-page.txt", artifacts.get(0).getStorageKey());
    }

    private DefaultToolRunner newRunner(Fixture fixture) throws Exception {
        DefaultToolRunner runner = new DefaultToolRunner();
        setField(runner, "ledgerFactService", fixture.ledgerFactService);
        setField(runner, "artifactRuntimeLedgerRecorder", fixture.artifactRecorder);
        setField(runner, "artifactRuntimeCollector", fixture.artifactCollector);
        setField(runner, "artifactRuntimeFormatter", fixture.artifactFormatter);
        return runner;
    }

    private ToolInvocationLedgerEntity findInvocation(Fixture fixture, String toolCallId) {
        return fixture.toolRepository.findByRunId("run-runtime").stream()
                .filter(item -> toolCallId.equals(item.getToolCallId()))
                .findFirst()
                .orElseThrow();
    }

    private void assertV2PayloadExcludesLegacyCopies(String responsePayloadJson) {
        Assert.assertTrue(responsePayloadJson.contains("\"schemaVersion\":2"));
        Assert.assertFalse(responsePayloadJson.contains("\"outputText\":"));
        Assert.assertFalse(responsePayloadJson.contains("\"outputData\":"));
        Assert.assertFalse(responsePayloadJson.contains("\"structuredOutput\":"));
        Assert.assertFalse(responsePayloadJson.contains("\"rawPayload\":"));
    }

    private ToolInvokeRequest request(String toolName, String toolCallId) {
        return ToolInvokeRequest.builder()
                .toolName(toolName)
                .toolCallId(toolCallId)
                .requestId("req-runtime")
                .sessionId("session-runtime")
                .step(1)
                .arguments(Map.of())
                .build();
    }

    private AgentContext context() {
        AgentContext context = AgentContext.builder()
                .requestId("req-runtime")
                .sessionId("session-runtime")
                .traceId("trace-runtime")
                .currentStep(1)
                .build();
        context.setAttribute("runId", "run-runtime");
        return context;
    }

    private static Map<String, Object> completeDescriptor(String artifactId,
                                                           String fileName,
                                                           String sourceType,
                                                           String storageRef,
                                                           int fileSize) {
        return Map.ofEntries(
                Map.entry("artifactId", artifactId),
                Map.entry("name", fileName),
                Map.entry("artifactType", "markdown"),
                Map.entry("artifactRole", "output"),
                Map.entry("visibility", "visible"),
                Map.entry("sourceType", sourceType),
                Map.entry("sourceName", sourceType),
                Map.entry("fileName", fileName),
                Map.entry("storageType", "local_artifact_storage"),
                Map.entry("storageRef", storageRef),
                Map.entry("storageKey", storageRef),
                Map.entry("contentSummary", "test artifact summary"),
                Map.entry("mimeType", "text/markdown"),
                Map.entry("fileSize", fileSize)
        );
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class ArtifactProducingTool implements BaseTool {

        @Override
        public String name() {
            return "artifact_save";
        }

        @Override
        public String description() {
            return "artifact producing tool";
        }

        @Override
        public Object params() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            Map<String, Object> artifact = completeDescriptor(
                    "artifact-runner-report", "runner-report.md", "artifact_save", "D:/tmp/runner-report.md", 88
            );
            return ToolResultPayload.builder()
                    .success(true)
                    .status("success")
                    .toolResult("已保存产物 runner-report.md")
                    .llmObservation("已保存产物：runner-report.md")
                    .structuredOutput(Map.of("path", "D:/tmp/runner-report.md"))
                    .artifactRefs(List.of("D:/tmp/runner-report.md"))
                    .artifactDescriptors(List.of(artifact))
                    .build();
        }
    }

    private static class WebFetchArtifactTool implements BaseTool {

        @Override
        public String name() {
            return "web_fetch";
        }

        @Override
        public String description() {
            return "controlled web fetch";
        }

        @Override
        public Object params() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            String body = "WEB_BODY_MUST_NOT_ENTER_TOOL_LEDGER".repeat(100);
            Map<String, Object> artifact = completeDescriptor(
                    "artifact-web-page", "web-page.txt", "web_fetch", "artifacts/run-runtime/web-page.txt", body.length()
            );
            return ToolResultPayload.builder()
                    .success(true)
                    .status("success")
                    .toolResult(body)
                    .llmObservation("已抓取网页：示例页面；artifactId=artifact-web-page；请用 artifact_read 分段读取正文。")
                    .structuredOutput(Map.of(
                            "url", "https://example.test/page",
                            "artifactId", "artifact-web-page",
                            "storageKey", "artifacts/run-runtime/web-page.txt",
                            "contentTruncated", false
                    ))
                    .artifactRefs(List.of("artifacts/run-runtime/web-page.txt"))
                    .artifactDescriptors(List.of(artifact))
                    .build();
        }
    }

    private static class ArtifactReadPreviewTool implements BaseTool {

        @Override
        public String name() {
            return "artifact_read";
        }

        @Override
        public String description() {
            return "controlled artifact preview";
        }

        @Override
        public Object params() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            String preview = "WEB_BODY_MUST_NOT_ENTER_TOOL_LEDGER".repeat(4);
            return ToolResultPayload.builder()
                    .success(true)
                    .status("success")
                    .toolResult(preview)
                    .llmObservation("已读取 artifact-web-page 的 0-120 字符预览；hasMore=true，nextOffset=120。"
                            + " 如需后续内容，请使用 artifact_read 并传入 offset=120。")
                    .structuredOutput(Map.of(
                            "artifactId", "artifact-web-page",
                            "storageKey", "artifacts/run-runtime/web-page.txt",
                            "contentLength", 3400,
                            "offset", 0,
                            "returnedLength", 120,
                            "hasMore", true,
                            "nextOffset", 120
                    ))
                    .build();
        }
    }

    private class Fixture {
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
        private final LedgerFactService ledgerFactService = new LedgerFactService(recordingService, queryService);
        private final ArtifactRuntimeCollector artifactCollector = new ArtifactRuntimeCollector();
        private final ArtifactRuntimeFormatter artifactFormatter = new ArtifactRuntimeFormatter();
        private final ArtifactRuntimeLedgerRecorder artifactRecorder = new ArtifactRuntimeLedgerRecorder(recordingService);
        private final AgentContext context = context();

        private void register(ToolInvokeRequest request, ToolExecutionResult result) {
            ArtifactRuntimeSource source = ArtifactRuntimeSource.from(context, request, result.getToolName());
            context.requireArtifactRuntimeRegistry().registerAll(source, artifactCollector.collect(request, result, context));
        }
    }
}
