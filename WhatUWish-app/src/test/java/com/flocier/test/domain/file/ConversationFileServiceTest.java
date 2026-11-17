package com.flocier.test.domain.file;

import com.flocier.application.agent.file.ConversationFileAccessException;
import com.flocier.application.agent.file.ConversationFileService;
import com.flocier.application.agent.file.ConversationFileSessionService;
import com.flocier.infrastructure.artifact.LocalArtifactContentStoreBackend;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import com.flocier.infrastructure.artifact.RoutingArtifactContentWriter;
import com.flocier.infrastructure.persistent.memory.InMemoryConversationFileRepository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

public class ConversationFileServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private InMemoryConversationFileRepository repository;
    private ConversationFileSessionService sessions;
    private ConversationFileService files;

    @Before
    public void setUp() {
        repository = new InMemoryConversationFileRepository();
        sessions = new ConversationFileSessionService(repository, 30);
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.getRoot().toString());
        RoutingArtifactContentWriter writer = new RoutingArtifactContentWriter(java.util.List.of(backend));
        files = new ConversationFileService(sessions, repository, writer, writer,
                new RoutingArtifactContentStore(java.util.List.of(backend)),
                "local_artifact_storage", "", 1024, 3, 2);
    }

    @Test
    public void sameNameHasDistinctIdentityAndOriginalBinaryBytesBeforeAnyRun() throws Exception {
        var session = sessions.create();
        byte[] first = {0, 1, -1, 10};
        byte[] second = {9, 8, 7};
        var a = files.upload(session.sessionId(), "../image.png", "image/png", first.length,
                new ByteArrayInputStream(first));
        var b = files.upload(session.sessionId(), "image.png", "image/png", second.length,
                new ByteArrayInputStream(second));
        assertEquals("image.png", a.name());
        assertEquals(a.name(), b.name());
        assertNotEquals(a.fileId(), b.fileId());
        assertEquals(2, files.list(session.sessionId()).size());
        try (var stream = files.download(session.sessionId(), a.fileId())) {
            assertArrayEquals(first, stream.readAllBytes());
        }
        try (var stream = files.download(session.sessionId(), b.fileId())) {
            assertArrayEquals(second, stream.readAllBytes());
        }
        assertEquals(64, repository.findFile(a.fileId()).orElseThrow().fileHash().length());
    }

    @Test
    public void sessionAndFileOwnershipGuardContentAndRemoval() throws Exception {
        var owner = sessions.create();
        var other = sessions.create();
        byte[] bytes = "# safe".getBytes(StandardCharsets.UTF_8);
        var file = files.upload(owner.sessionId(), "readme.md", "text/markdown", bytes.length,
                new ByteArrayInputStream(bytes));
        assertEquals("markdown", file.preview().getKind());
        assertEquals(1, files.list(owner.sessionId()).size());
        assertCode("FILE_NOT_FOUND", () -> files.download(other.sessionId(), file.fileId()));
        files.remove(owner.sessionId(), file.fileId());
        assertCode("FILE_NOT_READY", () -> files.download(owner.sessionId(), file.fileId()));
        assertEquals("REMOVED", files.metadata(owner.sessionId(), file.fileId()).status());
    }

    @Test
    public void rejectsEmptyOversizedAndTruncatedUploadsBeforeStorage() throws Exception {
        var session = sessions.create();
        assertCode("FILE_EMPTY", () -> files.upload(session.sessionId(), "a.txt", "text/plain", 0,
                new ByteArrayInputStream(new byte[0])));
        assertCode("FILE_TOO_LARGE", () -> files.upload(session.sessionId(), "a.txt", "text/plain", 1025,
                new ByteArrayInputStream(new byte[1025])));
        assertCode("FILE_SIZE_MISMATCH", () -> files.upload(session.sessionId(), "a.txt", "text/plain", 3,
                new ByteArrayInputStream(new byte[2])));
        assertTrue(files.list(session.sessionId()).isEmpty());
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("v1")));
    }

    @Test
    public void invalidDeclaredPreviewTypesRemainDownloadableWithoutInlinePreview() throws Exception {
        var session = sessions.create();
        byte[] invalidText = {(byte) 0xc3, (byte) 0x28};
        var text = files.upload(session.sessionId(), "notes.md", "text/markdown",
                invalidText.length, new ByteArrayInputStream(invalidText));
        assertNull(text.preview());
        assertEquals("application/octet-stream", text.mimeType());
        try (var stream = files.download(session.sessionId(), text.fileId())) {
            assertArrayEquals(invalidText, stream.readAllBytes());
        }

        byte[] forgedImage = "not an image".getBytes(StandardCharsets.UTF_8);
        var image = files.upload(session.sessionId(), "photo.png", "image/png",
                forgedImage.length, new ByteArrayInputStream(forgedImage));
        assertNull(image.preview());
        assertEquals("application/octet-stream", image.mimeType());
    }

    @Test
    public void sessionLimitCountsUnavailableFilesUntilTheyAreRemoved() throws Exception {
        var session = sessions.create();
        byte[] bytes = "data".getBytes(StandardCharsets.UTF_8);
        var first = files.upload(session.sessionId(), "one.txt", "text/plain", bytes.length,
                new ByteArrayInputStream(bytes));
        for (int index = 0; index < 2; index++) {
            files.upload(session.sessionId(), "next.txt", "text/plain", bytes.length,
                    new ByteArrayInputStream(bytes));
        }
        assertCode("FILE_COUNT_LIMIT", () -> files.upload(session.sessionId(),
                "four.txt", "text/plain", bytes.length, new ByteArrayInputStream(bytes)));
        assertTrue(repository.changeStatus(first.fileId(), "READY", "UNAVAILABLE", null));
        files.remove(session.sessionId(), first.fileId());
        var replacement = files.upload(session.sessionId(), "replacement.txt", "text/plain",
                bytes.length, new ByteArrayInputStream(bytes));
        assertEquals("READY", replacement.status());
    }

    @Test
    public void selectionKeepsOrderAndRejectsDuplicateForeignAndRemovedFiles() throws Exception {
        var owner = sessions.create();
        var foreign = sessions.create();
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        var a = files.upload(owner.sessionId(), "same.txt", "text/plain", bytes.length,
                new ByteArrayInputStream(bytes));
        var b = files.upload(owner.sessionId(), "same.txt", "text/plain", bytes.length,
                new ByteArrayInputStream(bytes));
        var other = files.upload(foreign.sessionId(), "other.txt", "text/plain", bytes.length,
                new ByteArrayInputStream(bytes));
        assertEquals(List.of(b.fileId(), a.fileId()), files.resolveSelection(owner.sessionId(),
                List.of(b.fileId(), a.fileId())).stream().map(item -> item.fileId()).toList());
        assertCode("FILE_SELECTION_INVALID", () -> files.resolveSelection(owner.sessionId(),
                List.of(a.fileId(), a.fileId())));
        assertCode("FILE_NOT_FOUND", () -> files.resolveSelection(owner.sessionId(),
                List.of(other.fileId())));
        files.remove(owner.sessionId(), a.fileId());
        assertCode("FILE_NOT_READY", () -> files.resolveSelection(owner.sessionId(),
                List.of(a.fileId())));
    }

    @Test
    public void interruptedUploadDoesNotReserveSlotOrLeaveStoredObject() throws Exception {
        var session = sessions.create();
        InputStream interrupted = new InputStream() {
            private int reads;
            @Override public int read() throws IOException {
                if (reads++ == 0) return 'a';
                throw new IOException("upload interrupted");
            }
        };
        assertThrows(IOException.class, () -> files.upload(session.sessionId(),
                "interrupted.txt", "text/plain", 2, interrupted));
        assertTrue(files.list(session.sessionId()).isEmpty());
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("v1")));
        assertTrue(repository.reserveFileSlot(session.sessionId(), 1));
        repository.releaseFileSlot(session.sessionId());
    }

    @Test
    public void metadataFailureRemovesStoredBytesAndReleasesSessionSlot() throws Exception {
        var session = sessions.create();
        var failingRepository = spy(repository);
        doThrow(new IllegalStateException("metadata write failed")).when(failingRepository).save(any());
        LocalArtifactContentStoreBackend backend = new LocalArtifactContentStoreBackend(temporary.getRoot().toString());
        RoutingArtifactContentWriter writer = new RoutingArtifactContentWriter(java.util.List.of(backend));
        var failingFiles = new ConversationFileService(sessions, failingRepository, writer, writer,
                new RoutingArtifactContentStore(java.util.List.of(backend)),
                "local_artifact_storage", "", 1024, 1, 1);
        byte[] bytes = "recover".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> failingFiles.upload(session.sessionId(),
                "recover.txt", "text/plain", bytes.length, new ByteArrayInputStream(bytes)));
        assertTrue(repository.listFiles(session.sessionId()).isEmpty());
        assertTrue(repository.reserveFileSlot(session.sessionId(), 1));
        repository.releaseFileSlot(session.sessionId());
        Path storage = temporary.getRoot().toPath().resolve("v1");
        if (Files.exists(storage)) {
            try (var paths = Files.walk(storage)) {
                assertEquals(0, paths.filter(Files::isRegularFile).count());
            }
        }
    }

    private void assertCode(String expected, ThrowingAction action) throws Exception {
        try {
            action.run();
            fail("Expected " + expected);
        } catch (ConversationFileAccessException exception) {
            assertEquals(expected, exception.getCode());
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }
}
