package com.flocier.test.domain.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.WorkspaceFileTool;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;
import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.infrastructure.artifact.LocalArtifactContentStoreBackend;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import com.flocier.infrastructure.persistent.memory.InMemoryConversationFileRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;

public class RunWorkspaceServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final InMemoryConversationFileRepository repository = new InMemoryConversationFileRepository();

    @Test
    public void materializesDistinctBinaryInputsAndKeepsSessionWorkspace() throws Exception {
        Path storage = temporary.newFolder("storage").toPath();
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(storage.toString());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("runs").getPath());
        byte[] first = {0, 1, -1, 10};
        byte[] second = {7, 8, 9};
        ConversationFileRecord a = stored(storage, first, "same.png");
        ConversationFileRecord b = stored(storage, second, "same.png");
        AgentContext context = AgentContext.builder().sessionId("session").build();
        var files = workspaces.prepare(context, List.of(a, b));
        assertEquals(2, files.size());
        assertNotEquals(files.get(0).path(), files.get(1).path());
        assertArrayEquals(first, Files.readAllBytes(workspaces.readable(context, files.get(0).path())));
        assertArrayEquals(second, Files.readAllBytes(workspaces.readable(context, files.get(1).path())));
        Path root = workspaces.root(context);
        assertThrows(IllegalArgumentException.class, () -> workspaces.writable(context, files.get(0).path()));
        assertThrows(IllegalArgumentException.class, () -> workspaces.readable(context, "../secret.txt"));
        workspaces.finish(context);
        assertTrue(Files.isDirectory(root));
        assertThrows(IllegalArgumentException.class, () -> workspaces.root(context));
        AgentContext next = AgentContext.builder().sessionId("session").build();
        workspaces.prepare(next, List.of());
        assertEquals(root, workspaces.root(next));
        assertArrayEquals(first, Files.readAllBytes(workspaces.readable(next, files.get(0).path())));
        workspaces.finish(next);
    }

    @Test
    public void keepsOutputsAcrossRunsAndRejectsConcurrentRunInSameSession() throws Exception {
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store-cross-run").getPath());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("work-cross-run").getPath());
        AgentContext first = AgentContext.builder().sessionId("session").build();
        AgentContext second = AgentContext.builder().sessionId("session").build();
        AgentContext other = AgentContext.builder().sessionId("other-session").build();
        workspaces.prepare(first, List.of());
        Path firstRoot = workspaces.root(first);
        Files.writeString(workspaces.writable(first, "outputs/report.md"), "first version");
        assertThrows(IllegalStateException.class, () -> workspaces.prepare(second, List.of()));
        workspaces.prepare(other, List.of());
        assertNotEquals(firstRoot, workspaces.root(other));
        assertFalse(Files.exists(workspaces.root(other).resolve("outputs/report.md")));
        workspaces.finish(other);
        workspaces.finish(first);
        workspaces.prepare(second, List.of());
        assertEquals(firstRoot, workspaces.root(second));
        assertEquals("first version", Files.readString(workspaces.readable(second, "outputs/report.md")));
        workspaces.finish(second);
    }

    @Test
    public void removedInputIsHiddenAndExpiredCleanupSkipsActiveRun() throws Exception {
        Path storage = temporary.newFolder("store-expiry").toPath();
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(storage.toString());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("work-expiry").getPath());
        ConversationFileRecord file = stored(storage, "original".getBytes(), "source.txt");
        AgentContext first = AgentContext.builder().sessionId("session").build();
        String input = workspaces.prepare(first, List.of(file)).get(0).path();
        assertEquals(List.of(input), workspaces.authorizedInputPaths(first));
        Path root = workspaces.root(first);
        workspaces.finish(first);
        assertTrue(repository.changeStatus(file.fileId(), "READY", "REMOVED", Instant.now()));
        AgentContext next = AgentContext.builder().sessionId("session").build();
        workspaces.prepare(next, List.of());
        assertTrue(workspaces.authorizedInputPaths(next).isEmpty());
        assertFalse(call("workspace_list", workspaces, next, Map.of()).getLlmObservation().contains(input));
        assertThrows(IllegalArgumentException.class, () -> workspaces.readable(next, input));
        workspaces.removeInput(file);
        assertFalse(Files.exists(root.resolve(input)));
        repository.createSession(new com.flocier.domain.agent.runtime.file.ConversationFileSession(
                "expired", "token", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
        AgentContext expired = AgentContext.builder().sessionId("expired").build();
        workspaces.prepare(expired, List.of());
        Path expiredRoot = workspaces.root(expired);
        workspaces.cleanupExpiredSessions();
        assertTrue(Files.exists(expiredRoot));
        workspaces.finish(expired);
        workspaces.cleanupExpiredSessions();
        assertFalse(Files.exists(expiredRoot));
        workspaces.finish(next);
    }

    @Test
    public void editingInputCreatesPersistentOutputCopyWithoutChangingOriginal() throws Exception {
        Path storage = temporary.newFolder("store-copy").toPath();
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(storage.toString());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("work-copy").getPath());
        ConversationFileRecord file = stored(storage, "original text".getBytes(), "notes.txt");
        AgentContext first = AgentContext.builder().sessionId("session").build();
        String input = workspaces.prepare(first, List.of(file)).get(0).path();
        assertTrue(call("workspace_read", workspaces, first, Map.of("path", input)).isSuccess());
        ToolResultPayload edit = call("workspace_edit", workspaces, first,
                Map.of("path", input, "old_string", "original", "new_string", "updated"));
        assertTrue(edit.getLlmObservation(), edit.isSuccess());
        String output = "outputs/" + Path.of(input).getFileName();
        assertTrue(edit.getLlmObservation().contains(output));
        assertEquals("original text", Files.readString(workspaces.readable(first, input)));
        assertEquals("updated text", Files.readString(workspaces.readable(first, output)));
        workspaces.finish(first);
        AgentContext next = AgentContext.builder().sessionId("session").build();
        assertEquals(input, workspaces.prepare(next, List.of(file)).get(0).path());
        assertEquals("updated text", Files.readString(workspaces.readable(next, output)));
        workspaces.finish(next);
    }

    @Test
    public void textToolsReturnUsefulPathsAndRejectInputOverwrite() throws Exception {
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store").getPath());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("runs").getPath());
        AgentContext context = AgentContext.builder().sessionId("session").build();
        workspaces.prepare(context, List.of());
        try {
            assertTrue(call("workspace_write", workspaces, context,
                    Map.of("path", "outputs/notes.md", "content", "first line\nimportant finding\n")).isSuccess());
            ToolResultPayload read = call("workspace_read", workspaces, context,
                    Map.of("path", "outputs/notes.md", "start_line", 2));
            assertTrue(read.getLlmObservation().contains("important finding"));
            assertTrue(read.getLlmObservation().contains("nextLine"));
            ToolResultPayload repeated = call("workspace_read", workspaces, context,
                    Map.of("path", "outputs/notes.md", "start_line", 2));
            assertTrue(repeated.getLlmObservation().contains("\"unchanged\":true"));
            assertTrue(call("workspace_grep", workspaces, context,
                    Map.of("pattern", "important")).getLlmObservation().contains("outputs/notes.md"));
            assertTrue(call("workspace_glob", workspaces, context,
                    Map.of("pattern", "outputs/*.md")).getLlmObservation().contains("outputs/notes.md"));
            assertFalse(call("workspace_write", workspaces, context,
                    Map.of("path", "inputs/notes.md", "content", "bad")).isSuccess());
        } finally {
            workspaces.finish(context);
        }
    }

    @Test
    public void digestMismatchFailsBeforeAgentCanUseFile() throws Exception {
        Path storage = temporary.newFolder("storage").toPath();
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(storage.toString());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("runs").getPath());
        ConversationFileRecord valid = stored(storage, new byte[]{1, 2, 3}, "a.bin");
        ConversationFileRecord wrong = new ConversationFileRecord(valid.fileId(), valid.sessionId(), valid.displayName(),
                valid.mimeType(), valid.fileSize(), "0".repeat(64), valid.storageType(), valid.storageKey(),
                valid.status(), valid.createdAt(), null);
        AgentContext context = AgentContext.builder().sessionId("session").build();
        assertThrows(IllegalStateException.class, () -> workspaces.prepare(context, List.of(wrong)));
        assertThrows(IllegalArgumentException.class, () -> workspaces.root(context));
        assertEquals(1, workspaces.prepare(context, List.of(valid)).size());
        workspaces.finish(context);
    }

    @Test
    public void cleanupFailureForOneSessionDoesNotBlockAnother() throws Exception {
        InMemoryConversationFileRepository failingRepository = new InMemoryConversationFileRepository() {
            @Override public java.util.Optional<com.flocier.domain.agent.runtime.file.ConversationFileSession> findSession(String id) {
                if ("broken".equals(id)) throw new IllegalStateException("repository unavailable");
                return super.findSession(id);
            }
        };
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store-cleanup").getPath());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), failingRepository, temporary.newFolder("work-cleanup").getPath());
        failingRepository.createSession(new com.flocier.domain.agent.runtime.file.ConversationFileSession(
                "expired", "token", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
        java.util.Map<String, Path> roots = new java.util.HashMap<>();
        for (String id : List.of("broken", "expired")) {
            AgentContext context = AgentContext.builder().sessionId(id).build();
            workspaces.prepare(context, List.of());
            roots.put(id, workspaces.root(context));
            workspaces.finish(context);
        }
        workspaces.cleanupExpiredSessions();
        assertFalse(Files.exists(roots.get("expired")));
        assertTrue(Files.exists(roots.get("broken")));
    }

    @Test
    public void rejectsSymbolicLinkWorkspaceEscape() throws Exception {
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store-link").getPath());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("runs-link").getPath());
        AgentContext context = AgentContext.builder().sessionId("session").build();
        workspaces.prepare(context, List.of());
        try {
            Path outside = temporary.newFolder("outside").toPath();
            Path link = workspaces.root(context).resolve("outputs/link");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (FileSystemException unsupported) {
                assumeNoException("当前账户没有创建符号链接的权限", unsupported);
            }
            assertThrows(IllegalArgumentException.class, () -> workspaces.writable(context, "outputs/link/escaped.txt"));
            assertThrows(IllegalArgumentException.class, () -> workspaces.readable(context, "outputs/link/escaped.txt"));
        } finally { workspaces.finish(context); }
    }

    @Test
    public void rejectsOversizedWriteAndGlobsOutputFiles() throws Exception {
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store-budget").getPath());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("runs-budget").getPath());
        AgentContext context = AgentContext.builder().sessionId("session").build();
        workspaces.prepare(context, List.of());
        try {
            assertThrows(IllegalArgumentException.class, () -> workspaces.checkWriteBudget(context,
                    workspaces.writable(context, "outputs/big.txt"), 33L * 1024 * 1024));
            assertTrue(call("workspace_write", workspaces, context,
                    Map.of("path", "outputs/root.md", "content", "hello")).isSuccess());
            assertTrue(call("workspace_glob", workspaces, context,
                    Map.of("path", "outputs", "pattern", "**/*.md")).getLlmObservation().contains("outputs/root.md"));
        } finally { workspaces.finish(context); }
    }

    @Test
    public void editRequiresCurrentReadAndUniqueMatch() throws Exception {
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store-edit").getPath());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("runs-edit").getPath());
        AgentContext context = AgentContext.builder().sessionId("session").build();
        workspaces.prepare(context, List.of());
        try {
            Path path = workspaces.writable(context, "outputs/notes.txt");
            Files.writeString(path, "repeat repeat");
            var edit = Map.<String, Object>of("path", "outputs/notes.txt", "old_string", "repeat", "new_string", "changed");
            assertFalse(call("workspace_edit", workspaces, context, edit).isSuccess());
            assertTrue(call("workspace_read", workspaces, context, Map.of("path", "outputs/notes.txt")).isSuccess());
            assertFalse(call("workspace_edit", workspaces, context, edit).isSuccess());
            Files.writeString(path, "repeat once");
            assertFalse(call("workspace_edit", workspaces, context, edit).isSuccess());
            assertTrue(call("workspace_read", workspaces, context, Map.of("path", "outputs/notes.txt")).isSuccess());
            assertTrue(call("workspace_edit", workspaces, context, edit).isSuccess());
            assertEquals("changed once", Files.readString(path));
        } finally { workspaces.finish(context); }
    }

    @Test
    public void longLineIsRejectedInsteadOfInventingContinuationLines() throws Exception {
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store-long").getPath());
        RunWorkspaceService workspaces = new RunWorkspaceService(
                new RoutingArtifactContentStore(List.of(backend)), repository, temporary.newFolder("runs-long").getPath());
        AgentContext context = AgentContext.builder().sessionId("session").build();
        workspaces.prepare(context, List.of());
        try {
            Files.writeString(workspaces.writable(context, "outputs/long.txt"), "x".repeat(12000) + "\nnext line\n");
            var result = call("workspace_read", workspaces, context, Map.of("path", "outputs/long.txt"));
            assertFalse(result.isSuccess());
            assertTrue(result.getLlmObservation().contains("单行文本超过读取上限"));
        } finally { workspaces.finish(context); }
    }

    private ConversationFileRecord stored(Path root, byte[] content, String name) throws Exception {
        String id = UUID.randomUUID().toString();
        String key = new ArtifactObjectKeyBuilder("").build(id);
        Path path = root.resolve(key);
        Files.createDirectories(path.getParent());
        Files.write(path, content);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        ConversationFileRecord file = new ConversationFileRecord(id, "session", name, "application/octet-stream", content.length,
                hash, "local_artifact_storage", key, "READY", Instant.now(), null);
        if (repository.findSession("session").isEmpty())
            repository.createSession(new com.flocier.domain.agent.runtime.file.ConversationFileSession(
                    "session", "token", Instant.now(), Instant.now().plusSeconds(3600)));
        repository.save(file);
        return file;
    }

    private ToolResultPayload call(String name, RunWorkspaceService workspaces, AgentContext context,
                                   Map<String, Object> args) {
        return (ToolResultPayload) new WorkspaceFileTool(name, workspaces, null).execute(
                ToolInvokeRequest.builder().toolName(name).arguments(args).build(), context);
    }
}
