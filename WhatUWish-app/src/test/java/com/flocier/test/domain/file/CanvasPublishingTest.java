package com.flocier.test.domain.file;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.canvas.CanvasPublishPresentation;
import com.flocier.domain.agent.runtime.agent.tool.canvas.CanvasPublishTool;
import com.flocier.domain.agent.runtime.agent.tool.canvas.GetHtmlCanvasGuideTool;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.file.WorkspaceArtifactRegistrationService;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IToolInvocationLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.infrastructure.artifact.LocalArtifactContentStoreBackend;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import com.flocier.infrastructure.persistent.memory.InMemoryConversationFileRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CanvasPublishingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void publishesOriginalHtmlOnceAndProjectsReplayFacts() throws Exception {
        var backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store").getPath());
        var workspace = new RunWorkspaceService(new RoutingArtifactContentStore(List.of(backend)),
                new InMemoryConversationFileRepository(), temporary.newFolder("runs").getPath());
        var context = AgentContext.builder().sessionId("session").requestId("request").traceId("trace").build();
        context.setAttribute("runId", "run");
        workspace.prepare(context, List.of());
        try {
            byte[] html = "<!doctype html><html><body><h1>中文画布</h1><script>window.x=1</script></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            var path = workspace.writable(context, "outputs/page.html");
            Files.write(path, html);
            var writes = new AtomicInteger();
            var stored = new AtomicReference<byte[]>();
            ArtifactContentWriter writer = request -> { writes.incrementAndGet(); stored.set(request.getContent());
                return new ArtifactStoredContent(request.getContent().length); };
            var existing = new HashMap<String, ArtifactLedgerEntity>();
            IArtifactLedgerRepository artifacts = mock(IArtifactLedgerRepository.class);
            when(artifacts.findByIdempotencyKey(anyString()))
                    .thenAnswer(call -> Optional.ofNullable(existing.get(call.getArgument(0))));
            IToolInvocationLedgerRepository invocations = mock(IToolInvocationLedgerRepository.class);
            when(invocations.findByRunId("run")).thenReturn(List.of(ToolInvocationLedgerEntity.builder()
                    .toolInvocationId("invocation").toolCallId("call").toolName("canvas_publish").build()));
            LedgerRecordingService ledger = mock(LedgerRecordingService.class);
            var recorded = new AtomicReference<ArtifactLedgerEntity>();
            when(ledger.recordArtifact(any())).thenAnswer(call -> { ArtifactLedgerEntity item = call.getArgument(0);
                existing.put(item.getIdempotencyKey(), item); recorded.set(item); return item; });
            var registration = new WorkspaceArtifactRegistrationService(workspace, writer,
                    mock(ArtifactContentRemover.class), artifacts, invocations, ledger,
                    new RuntimeEventMapper(), RuntimeEventPublisher.noop(), "local_artifact_storage", "");
            var tool = new CanvasPublishTool(workspace, registration);
            ToolResultPayload first = call(tool, context, "outputs/page.html", false);
            ToolResultPayload second = call(tool, context, "outputs/page.html", false);
            assertTrue(first.isSuccess()); assertTrue(second.isSuccess());
            assertEquals(1, writes.get()); assertArrayEquals(html, stored.get());
            assertEquals("html", JSON.parseObject(recorded.get().getMetadataJson())
                    .getJSONObject("preview").getString("kind"));
            assertEquals(first.getArtifactRefs(), second.getArtifactRefs());
            var presentation = CanvasPublishPresentation.fromStructured(first.getStructuredOutput());
            assertEquals(false, ((Map<?, ?>) presentation.get("data")).get("openInPanel"));
            assertNotNull(CanvasPublishPresentation.fromLedger(JSON.toJSONString(Map.of(
                    "success", true, "result", first.getStructuredOutput()))));
            assertFalse(call(tool, context, "inputs/page.html", true).isSuccess());
            assertFalse(call(tool, context, "../page.html", true).isSuccess());
            assertFalse(call(tool, context, "outputs/missing.html", true).isSuccess());
            Files.writeString(workspace.writable(context, "outputs/not-html.txt"), "hello");
            assertFalse(call(tool, context, "outputs/not-html.txt", true).isSuccess());
            Files.write(workspace.writable(context, "outputs/forged.html"), new byte[]{(byte) 0xc3, 0x28});
            assertFalse(call(tool, context, "outputs/forged.html", true).isSuccess());
            assertEquals(1, writes.get());
            assertNull(CanvasPublishPresentation.fromLedger(JSON.toJSONString(Map.of("success", false))));
        } finally { workspace.finish(context); }
    }

    @Test public void guideContainsCompleteCanvasInstructions() {
        var guide = (ToolResultPayload) new GetHtmlCanvasGuideTool().execute(null, null);
        assertTrue(guide.isSuccess());
        assertTrue(guide.getLlmObservation().contains("responsive_accessibility"));
        assertTrue(guide.getLlmObservation().contains("reference_template"));
    }

    private ToolResultPayload call(CanvasPublishTool tool, AgentContext context, String path, boolean open) {
        return (ToolResultPayload) tool.execute(ToolInvokeRequest.builder().toolName("canvas_publish")
                .toolCallId("call").arguments(Map.of("html_path", path, "title", "中文画布", "open_in_panel", open))
                .build(), context);
    }
}
