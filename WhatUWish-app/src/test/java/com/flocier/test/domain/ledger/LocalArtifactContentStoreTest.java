package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.infrastructure.artifact.LocalArtifactContentStoreBackend;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LocalArtifactContentStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldInspectPreviewAndStreamDownloadFromConfiguredRoot() throws Exception {
        byte[] content = "# Local artifact".getBytes(StandardCharsets.UTF_8);
        Path target = root().resolve("session/run/report.md");
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        LocalArtifactContentStoreBackend store = new LocalArtifactContentStoreBackend(root().toString());
        ArtifactContentLocator locator = locator("session/run/report.md");

        assertEquals(content.length, store.inspect(locator).getSize());
        assertArrayEquals(content, store.readPreview(locator, 1024));
        try (var stream = store.openDownload(locator)) {
            assertArrayEquals(content, stream.readAllBytes());
        }
    }

    @Test
    public void shouldRejectPathEscapeMissingAndNonRegularContentWithControlledCode() throws Exception {
        Files.createDirectory(root().resolve("directory-only"));
        LocalArtifactContentStoreBackend store = new LocalArtifactContentStoreBackend(root().toString());

        assertControlled(store, locator("../outside.md"));
        assertControlled(store, locator("missing.md"));
        assertControlled(store, locator("directory-only"));
        assertControlled(store, locator(null));
    }

    @Test
    public void shouldRejectPreviewBeyondBudgetWhileDownloadRemainsStreaming() throws Exception {
        byte[] content = new byte[4097];
        Path target = root().resolve("large.bin");
        Files.write(target, content);
        LocalArtifactContentStoreBackend store = new LocalArtifactContentStoreBackend(root().toString());
        ArtifactContentLocator locator = locator("large.bin");

        ArtifactContentStoreException exception = assertThrows(ArtifactContentStoreException.class,
                () -> store.readPreview(locator, 4096));
        assertEquals("PREVIEW_LIMIT", exception.getMessage());
        try (var stream = store.openDownload(locator)) {
            assertEquals(32, stream.readNBytes(32).length);
        }
    }

    private ArtifactContentLocator locator(String key) {
        return new ArtifactContentLocator(ArtifactStorageType.LOCAL, key, null);
    }

    private void assertControlled(LocalArtifactContentStoreBackend store, ArtifactContentLocator locator) {
        ArtifactContentStoreException exception = assertThrows(ArtifactContentStoreException.class,
                () -> store.inspect(locator));
        assertEquals("ARTIFACT_UNAVAILABLE", exception.getMessage());
    }

    private Path root() {
        return temporaryFolder.getRoot().toPath();
    }
}
