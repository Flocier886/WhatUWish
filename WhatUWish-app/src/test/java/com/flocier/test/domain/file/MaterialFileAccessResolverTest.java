package com.flocier.test.domain.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import com.flocier.infrastructure.persistent.memory.InMemoryConversationFileRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 材料工具接收会话工作区文件与已授权 Artifact；嵌套引用也不得绕过同一入口。 */
public class MaterialFileAccessResolverTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void mapsRunOutputToSharedWorkspacePath() throws Exception {
        Fixture fixture = new Fixture();
        try {
            byte[] expected = "name,amount\nAda,4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(fixture.workspace.writable(fixture.context, "outputs/table.csv"), expected);
            var prepared = fixture.resolver.prepare(fixture.context, "csv_processor",
                    Map.of("file_path", "outputs/table.csv"));
            assertEquals("outputs/table.csv", prepared.arguments().get("file_path"));
            assertEquals("outputs/table.csv", prepared.files().get(0).path());
            assertEquals(fixture.workspace.root(fixture.context).toString(), prepared.workspaceRoot());
        } finally { fixture.close(); }
    }

    @Test
    public void mapsPriorRunOutputWithoutRecreatingIt() throws Exception {
        Fixture fixture = new Fixture();
        AgentContext next = AgentContext.builder().sessionId("session-material").build();
        try {
            Files.writeString(fixture.workspace.writable(fixture.context, "outputs/report.csv"), "name\nAda\n");
            Path root = fixture.workspace.root(fixture.context);
            fixture.workspace.finish(fixture.context);
            next.setAttribute("runId", "next-run");
            fixture.workspace.prepare(next, List.of());
            var prepared = fixture.resolver.prepare(next, "csv_processor",
                    Map.of("file_path", "outputs/report.csv"));
            assertEquals(root.toString(), prepared.workspaceRoot());
            assertEquals("outputs/report.csv", prepared.arguments().get("file_path"));
            assertEquals("name\nAda\n", Files.readString(fixture.workspace.readable(next, "outputs/report.csv")));
        } finally {
            fixture.workspace.finish(next);
            fixture.close();
        }
    }

    @Test
    public void mapsPriorSessionInputWithoutCurrentRunArtifact() throws Exception {
        Fixture fixture = new Fixture();
        try {
            String fileId = UUID.randomUUID().toString();
            String path = "inputs/" + fileId + "-sample.pdf";
            byte[] expected = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);
            Files.write(fixture.workspace.root(fixture.context).resolve(path), expected);
            fixture.files.save(new com.flocier.domain.agent.runtime.file.ConversationFileRecord(fileId,
                    "session-material", "sample.pdf", "application/pdf", expected.length,
                    "hash", "local_artifact_storage", "key", "READY", java.time.Instant.now(), null));
            var prepared = fixture.resolver.prepare(fixture.context, "pdf_reader", Map.of("file_path", path));
            assertEquals(path, prepared.arguments().get("file_path"));
            assertEquals(path, prepared.files().get(0).path());
        } finally { fixture.close(); }
    }

    @Test
    public void rejectsNestedUnauthorizedAndDeletedArtifactReferences() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.artifacts.save(artifact("other-session-id", "another-session", 0));
            fixture.artifacts.save(artifact("deleted-id", "session-material", 1));
            for (String id : List.of("other-session-id", "deleted-id")) {
                assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                        "sql_query", Map.of("query", "SELECT * FROM sales",
                                "tables", Map.of("sales", Map.of("artifactId", id)))));
            }
            assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                    "data_merge", Map.of("left_data", List.of(Map.of("amount", 4)),
                            "right_artifact", "file:///private/table.csv")));
        } finally { fixture.close(); }
    }

    @Test
    public void materializesVisibleArtifactOnceAndReusesItsRunPath() throws Exception {
        Fixture fixture = new Fixture();
        try {
            byte[] content = "name\nAda".getBytes(StandardCharsets.UTF_8);
            fixture.artifacts.save(artifact("saved-id", "session-material", 0));
            when(fixture.store.openDownload(org.mockito.ArgumentMatchers.any())).thenAnswer(
                    ignored -> new ByteArrayInputStream(content));
            var first = fixture.resolver.prepare(fixture.context, "csv_processor",
                    Map.of("artifact", "saved-id"));
            String path = String.valueOf(first.arguments().get("artifact"));
            assertTrue(path.startsWith("inputs/artifacts/saved-id-"));
            assertArrayEquals(content, Files.readAllBytes(fixture.workspace.readable(fixture.context, path)));
            var second = fixture.resolver.prepare(fixture.context, "csv_processor",
                    Map.of("artifact", "saved-id"));
            assertEquals(path, second.arguments().get("artifact"));
            org.mockito.Mockito.verify(fixture.store, org.mockito.Mockito.times(1))
                    .openDownload(org.mockito.ArgumentMatchers.any());
        } finally { fixture.close(); }
    }

    @Test
    public void mapsDocumentImageIdsThroughAuthorizedArtifactAccess() throws Exception {
        Fixture fixture = new Fixture();
        try {
            String id = UUID.randomUUID().toString().replace("-", "");
            ArtifactLedgerEntity image = artifact(id, "session-material", 0);
            image.setFileName("figure.png");
            fixture.artifacts.save(image);
            when(fixture.store.openDownload(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(ignored -> new ByteArrayInputStream(new byte[8]));
            var prepared = fixture.resolver.prepare(fixture.context, "document_generate", Map.of(
                    "content", "![图](" + id + ")",
                    "blocks", List.of(Map.of("type", "image", "file_id", id))));
            String content = String.valueOf(prepared.arguments().get("content"));
            assertTrue(content.contains("inputs/artifacts/" + id + "-figure.png"));
            Map<?, ?> block = (Map<?, ?>) ((List<?>) prepared.arguments().get("blocks")).get(0);
            assertEquals("inputs/artifacts/" + id + "-figure.png", block.get("path"));
            assertFalse(block.containsKey("file_id"));
            ArtifactLedgerEntity other = artifact(UUID.randomUUID().toString(), "another-session", 0);
            fixture.artifacts.save(other);
            assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                    "document_generate", Map.of("blocks", List.of(Map.of("type", "image",
                            "file_id", other.getArtifactId())))));
        } finally { fixture.close(); }
    }

    @Test
    public void authorizesRetrievedSourcesForCodeToolsWithoutForwardingIds() throws Exception {
        Fixture fixture = new Fixture();
        try {
            byte[] content = "source facts".getBytes(StandardCharsets.UTF_8);
            ArtifactLedgerEntity webpage = artifact("web-source", "session-material", 0);
            webpage.setArtifactRole("source");
            webpage.setArtifactType("web_page");
            webpage.setSourceType("web_fetch");
            webpage.setSourceName("web_fetch");
            webpage.setFileName("web-page.txt");
            webpage.setFileSize((long) content.length);
            webpage.setRunId("previous-run");
            fixture.artifacts.save(webpage);
            ArtifactLedgerEntity packageFile = artifact("deep-source", "session-material", 0);
            packageFile.setArtifactType("research_source_package");
            packageFile.setSourceType("deep_search");
            packageFile.setSourceName("deep_search_tool");
            packageFile.setFileName("deepsearch-source-package.md");
            packageFile.setFileSize((long) content.length);
            packageFile.setRunId("previous-run");
            fixture.artifacts.save(packageFile);
            when(fixture.store.openDownload(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(ignored -> new ByteArrayInputStream(content));

            var direct = fixture.resolver.prepare(fixture.context, "code_execution",
                    Map.of("source", "print('ok')", "sourceArtifactIds", List.of("web-source")));
            assertFalse(direct.arguments().containsKey("sourceArtifactIds"));
            List<?> directPaths = (List<?>) direct.arguments().get("__authorizedInputPaths");
            assertEquals(1, directPaths.size());
            assertTrue(String.valueOf(directPaths.get(0)).startsWith("inputs/artifacts/web-source-"));
            assertEquals("source facts", Files.readString(fixture.workspace.readable(fixture.context,
                    String.valueOf(directPaths.get(0)))));

            var multistep = fixture.resolver.prepare(fixture.context, "code_interpreter",
                    Map.of("task", "analyze", "sourceArtifactIds", List.of("web-source", "deep-source")));
            assertFalse(multistep.arguments().containsKey("sourceArtifactIds"));
            assertEquals(2, ((List<?>) multistep.arguments().get("__authorizedInputPaths")).size());
            assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                    "code_execution", Map.of("source", "print(1)", "sourceArtifactIds", List.of("web-source", "web-source"))));
        } finally { fixture.close(); }
    }

    @Test
    public void rematerializesRetrievedSourceAfterPriorRunFinishes() throws Exception {
        Fixture fixture = new Fixture();
        AgentContext next = AgentContext.builder().sessionId("session-material").build();
        try {
            byte[] content = "prior run source".getBytes(StandardCharsets.UTF_8);
            ArtifactLedgerEntity webpage = artifact("prior-web", "session-material", 0);
            webpage.setRunId("old-run");
            webpage.setArtifactRole("source");
            webpage.setArtifactType("web_page");
            webpage.setSourceType("web_fetch");
            webpage.setSourceName("web_fetch");
            webpage.setFileSize((long) content.length);
            fixture.artifacts.save(webpage);
            when(fixture.store.openDownload(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(ignored -> new ByteArrayInputStream(content));
            var first = fixture.resolver.prepare(fixture.context, "code_execution",
                    Map.of("source", "print(1)", "sourceArtifactIds", List.of("prior-web")));
            String path = String.valueOf(((List<?>) first.arguments().get("__authorizedInputPaths")).get(0));
            Path root = fixture.workspace.root(fixture.context);
            fixture.workspace.finish(fixture.context);
            assertFalse(Files.exists(root.resolve(path)));
            next.setAttribute("runId", "new-run");
            fixture.workspace.prepare(next, List.of());
            var prepared = fixture.resolver.prepare(next, "code_execution",
                    Map.of("source", "print(1)", "sourceArtifactIds", List.of("prior-web")));
            String nextPath = String.valueOf(((List<?>) prepared.arguments().get("__authorizedInputPaths")).get(0));
            assertEquals(path, nextPath);
            assertEquals("prior run source", Files.readString(fixture.workspace.readable(next, nextPath)));
            org.mockito.Mockito.verify(fixture.store, org.mockito.Mockito.times(2))
                    .openDownload(org.mockito.ArgumentMatchers.any());
        } finally {
            fixture.workspace.finish(next);
            fixture.close();
        }
    }

    @Test
    public void rejectsUnauthorizedRetrievedSourcesBeforeCodeRuns() throws Exception {
        Fixture fixture = new Fixture();
        try {
            ArtifactLedgerEntity wrongSession = artifact("other-source", "other-session", 0);
            wrongSession.setArtifactType("research_source_package");
            wrongSession.setSourceType("deep_search");
            wrongSession.setSourceName("deep_search_tool");
            fixture.artifacts.save(wrongSession);
            ArtifactLedgerEntity deleted = artifact("deleted-source", "session-material", 1);
            deleted.setArtifactType("research_source_package");
            deleted.setSourceType("deep_search");
            deleted.setSourceName("deep_search_tool");
            fixture.artifacts.save(deleted);
            fixture.artifacts.save(artifact("plain-output", "session-material", 0));
            for (String id : List.of("other-source", "deleted-source", "plain-output")) {
                assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                        "code_execution", Map.of("source", "print(1)", "sourceArtifactIds", List.of(id))));
            }
            assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                    "code_execution", Map.of("source", "print(1)", "sourceArtifactIds", List.of("inputs/private.txt"))));
        } finally { fixture.close(); }
    }

    @Test
    public void rejectsOversizedOrUnreadableRetrievedSource() throws Exception {
        Fixture fixture = new Fixture();
        try {
            ArtifactLedgerEntity tooLarge = analysisWebSource("too-large", "session-material", 0);
            tooLarge.setFileSize(32L * 1024 * 1024 + 1);
            fixture.artifacts.save(tooLarge);
            assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                    "code_execution", Map.of("source", "print(1)", "sourceArtifactIds", List.of("too-large"))));

            ArtifactLedgerEntity unavailable = analysisWebSource("unavailable", "session-material", 0);
            fixture.artifacts.save(unavailable);
            when(fixture.store.openDownload(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new IllegalStateException("storage offline"));
            assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                    "code_interpreter", Map.of("task", "analyze", "sourceArtifactIds", List.of("unavailable"))));
        } finally { fixture.close(); }
    }

    private static ArtifactLedgerEntity analysisWebSource(String id, String sessionId, int deleted) {
        ArtifactLedgerEntity source = artifact(id, sessionId, deleted);
        source.setArtifactRole("source");
        source.setArtifactType("web_page");
        source.setSourceType("web_fetch");
        source.setSourceName("web_fetch");
        return source;
    }

    @Test
    public void rejectsSymlinkAndParentEscapeBeforeResolvingPaths() throws Exception {
        Fixture fixture = new Fixture();
        try {
            Path outside = temporary.newFolder("outside").toPath();
            Files.writeString(outside.resolve("private.csv"), "secret");
            Path link = fixture.workspace.root(fixture.context).resolve("outputs/link");
            try { Files.createSymbolicLink(link, outside); }
            catch (FileSystemException unsupported) { assumeNoException(unsupported); }
            assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                    "csv_processor", Map.of("file_path", "outputs/link/private.csv")));
            assertThrows(IllegalArgumentException.class, () -> fixture.resolver.prepare(fixture.context,
                    "csv_processor", Map.of("file_path", "outputs/../private.csv")));
        } finally { fixture.close(); }
    }

    private static ArtifactLedgerEntity artifact(String id, String sessionId, int deleted) {
        return ArtifactLedgerEntity.builder().artifactId(id).sessionId(sessionId)
                .artifactRole("output").visibility("visible").deleted(deleted)
                .storageType("local_artifact_storage").storageKey("v1/artifacts/" + id + "/content")
                .fileName("table.csv").fileSize(8L).build();
    }

    private final class Fixture {
        private final InMemoryArtifactLedgerRepository artifacts = new InMemoryArtifactLedgerRepository();
        private final InMemoryConversationFileRepository files = new InMemoryConversationFileRepository();
        private final AgentContext context = AgentContext.builder().sessionId("session-material").build();
        private final RunWorkspaceService workspace;
        private final ArtifactContentStore store = mock(ArtifactContentStore.class);
        private final MaterialFileAccessResolver resolver;

        private Fixture() throws Exception {
            files.createSession(new com.flocier.domain.agent.runtime.file.ConversationFileSession(
                    "session-material", "token", java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600)));
            workspace = new RunWorkspaceService(store, files, temporary.newFolder("runs").getPath());
            context.setAttribute("runId", "run-material");
            workspace.prepare(context, List.of());
            resolver = new MaterialFileAccessResolver(workspace, artifacts, store);
        }

        private void close() { workspace.finish(context); }
    }
}
