package com.flocier.test.domain.file;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class WorkspaceArtifactRegistrationServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void registersHostCliOutputAgainstTrustedInvocation() throws Exception {
        var backend = new LocalArtifactContentStoreBackend(temporary.newFolder("host-cli-store").getPath());
        var workspace = new RunWorkspaceService(new RoutingArtifactContentStore(List.of(backend)),
                new InMemoryConversationFileRepository(), temporary.newFolder("host-cli-runs").getPath());
        var context = AgentContext.builder().sessionId("session").requestId("request").traceId("trace").build();
        context.setAttribute("runId", "run");
        workspace.prepare(context, List.of());
        try {
            var output = workspace.writable(context, "outputs/search.txt");
            Files.writeString(output, "原始搜索结果");
            ArtifactContentWriter writer = request -> new ArtifactStoredContent(request.getContent().length);
            IArtifactLedgerRepository artifacts = mock(IArtifactLedgerRepository.class);
            when(artifacts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            IToolInvocationLedgerRepository invocations = mock(IToolInvocationLedgerRepository.class);
            when(invocations.findByRunId("run")).thenReturn(List.of(ToolInvocationLedgerEntity.builder()
                    .toolInvocationId("cli-invocation").toolCallId("cli-call").toolName("host_cli").build()));
            LedgerRecordingService ledger = mock(LedgerRecordingService.class);
            AtomicReference<ArtifactLedgerEntity> saved = new AtomicReference<>();
            when(ledger.recordArtifact(any())).thenAnswer(call -> {
                ArtifactLedgerEntity entity = call.getArgument(0);
                saved.set(entity);
                return entity;
            });
            var registration = new WorkspaceArtifactRegistrationService(workspace, writer,
                    mock(ArtifactContentRemover.class), artifacts, invocations, ledger,
                    new RuntimeEventMapper(), RuntimeEventPublisher.noop(), "local_artifact_storage", "");

            assertThrows(IllegalArgumentException.class,
                    () -> registration.registerHostCliOutput(context, output, "other-call"));
            var result = registration.registerHostCliOutput(context, output, "cli-call");
            assertEquals("outputs/search.txt", result.get("path"));
            assertEquals(saved.get().getArtifactId(), result.get("artifactId"));
            assertEquals("cli-invocation", saved.get().getToolInvocationId());
            assertEquals("host_cli", saved.get().getSourceName());
            assertEquals("visible", saved.get().getVisibility());
            var inputFile = workspace.root(context).resolve("inputs/forbidden.txt");
            Files.writeString(inputFile, "not output");
            assertThrows(IllegalArgumentException.class,
                    () -> registration.registerHostCliOutput(context, inputFile, "cli-call"));
        } finally {
            workspace.finish(context);
        }
    }

    @Test
    public void registersOriginalImageBytesOnceWithTrustedInvocationAndPreview() throws Exception {
        var backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store").getPath());
        var workspace = new RunWorkspaceService(new RoutingArtifactContentStore(List.of(backend)),
                new InMemoryConversationFileRepository(),
                temporary.newFolder("runs").getPath());
        var context = AgentContext.builder().sessionId("session").requestId("request").traceId("trace").build();
        context.setAttribute("runId", "run");
        workspace.prepare(context, List.of());
        try {
            byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0};
            var path = workspace.writable(context, "outputs/chart.png");
            Files.write(path, png);
            var saved = new AtomicReference<ArtifactLedgerEntity>();
            var savedByKey = new java.util.HashMap<String, ArtifactLedgerEntity>();
            var writes = new AtomicInteger();
            var written = new AtomicReference<ArtifactContentWriteRequest>();
            var versions = new ArrayList<byte[]>();
            ArtifactContentWriter writer = request -> {
                writes.incrementAndGet();
                written.set(request);
                versions.add(request.getContent().clone());
                return new ArtifactStoredContent(request.getContent().length);
            };
            IArtifactLedgerRepository artifacts = mock(IArtifactLedgerRepository.class);
            when(artifacts.findByIdempotencyKey(anyString()))
                    .thenAnswer(call -> Optional.ofNullable(savedByKey.get(call.getArgument(0))));
            IToolInvocationLedgerRepository invocations = mock(IToolInvocationLedgerRepository.class);
            when(invocations.findByRunId("run")).thenReturn(List.of(ToolInvocationLedgerEntity.builder()
                    .toolInvocationId("invocation").toolCallId("call").toolName("workspace_register").build()));
            LedgerRecordingService ledger = mock(LedgerRecordingService.class);
            when(ledger.recordArtifact(any())).thenAnswer(call -> {
                ArtifactLedgerEntity artifact = call.getArgument(0);
                saved.set(artifact);
                savedByKey.put(artifact.getIdempotencyKey(), artifact);
                return artifact;
            });
            var registration = new WorkspaceArtifactRegistrationService(workspace, writer,
                    mock(ArtifactContentRemover.class), artifacts, invocations, ledger,
                    new RuntimeEventMapper(), RuntimeEventPublisher.noop(), "local_artifact_storage", "");

            assertThrows(IllegalArgumentException.class, () -> registration.register(context, path, "untrusted"));
            assertEquals(true, registration.register(context, path, "call").get("created"));
            assertEquals(false, registration.register(context, path, "call").get("created"));
            assertEquals(1, writes.get());
            assertTrue(Arrays.equals(png, written.get().getContent()));
            assertEquals("invocation", saved.get().getToolInvocationId());
            assertEquals("image", JSON.parseObject(saved.get().getMetadataJson())
                    .getJSONObject("preview").getString("kind"));
            assertEquals("output", saved.get().getArtifactRole());

            String firstArtifactId = saved.get().getArtifactId();
            Files.write(path, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3});
            assertEquals(true, registration.register(context, path, "call").get("created"));
            assertNotEquals(firstArtifactId, saved.get().getArtifactId());
            assertEquals(2, writes.get());
            assertArrayEquals(png, versions.get(0));
            assertArrayEquals(Files.readAllBytes(path), versions.get(1));

            var markdown = workspace.writable(context, "outputs/readme.md");
            Files.writeString(markdown, "# Report");
            registration.register(context, markdown, "call");
            assertEquals("text/markdown", saved.get().getMimeType());
            assertEquals("markdown", JSON.parseObject(saved.get().getMetadataJson())
                    .getJSONObject("preview").getString("kind"));

            var invalid = workspace.writable(context, "outputs/invalid.md");
            Files.write(invalid, new byte[]{(byte) 0xc3, (byte) 0x28});
            registration.register(context, invalid, "call");
            assertEquals("application/octet-stream", saved.get().getMimeType());
            assertEquals("download-only", JSON.parseObject(saved.get().getMetadataJson())
                    .getJSONObject("preview").getString("kind"));
        } finally {
            workspace.finish(context);
        }
    }
}
