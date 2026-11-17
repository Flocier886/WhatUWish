package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import com.flocier.infrastructure.artifact.ArtifactContentStoreBackend;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RoutingArtifactContentStoreTest {

    @Test
    public void shouldDispatchOnlyByRegisteredStorageType() {
        CountingBackend local = new CountingBackend(ArtifactStorageType.LOCAL, 11);
        CountingBackend object = new CountingBackend(ArtifactStorageType.S3, 22);
        RoutingArtifactContentStore store = new RoutingArtifactContentStore(List.of(local, object));

        assertEquals(11, store.inspect(locator(ArtifactStorageType.LOCAL)).getSize());
        assertEquals(22, store.inspect(locator(ArtifactStorageType.S3)).getSize());
        assertEquals(1, local.calls.get());
        assertEquals(1, object.calls.get());
    }

    @Test
    public void shouldFailClosedForMissingOrUnknownStorageType() {
        CountingBackend local = new CountingBackend(ArtifactStorageType.LOCAL, 11);
        RoutingArtifactContentStore store = new RoutingArtifactContentStore(List.of(local));

        for (String storageType : new String[]{null, "", "unknown", "S3_OBJECT_STORAGE"}) {
            ArtifactContentStoreException exception = assertThrows(ArtifactContentStoreException.class,
                    () -> store.inspect(locator(storageType)));
            assertEquals("ARTIFACT_UNAVAILABLE", exception.getMessage());
        }
        assertEquals(0, local.calls.get());
    }

    @Test
    public void shouldRejectDuplicateStorageTypeRegistration() {
        CountingBackend first = new CountingBackend(ArtifactStorageType.LOCAL, 1);
        CountingBackend second = new CountingBackend(ArtifactStorageType.LOCAL, 2);

        assertThrows(IllegalStateException.class,
                () -> new RoutingArtifactContentStore(List.of(first, second)));
    }

    private ArtifactContentLocator locator(String storageType) {
        return new ArtifactContentLocator(storageType, "private/key", "compat/ref");
    }

    private static class CountingBackend implements ArtifactContentStoreBackend {
        private final String storageType;
        private final long size;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingBackend(String storageType, long size) {
            this.storageType = storageType;
            this.size = size;
        }

        @Override
        public Set<String> storageTypes() {
            return Set.of(storageType);
        }

        @Override
        public ArtifactStoredContent inspect(ArtifactContentLocator locator) {
            calls.incrementAndGet();
            return new ArtifactStoredContent(size);
        }

        @Override
        public InputStream openDownload(ArtifactContentLocator locator) {
            calls.incrementAndGet();
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public byte[] readPreview(ArtifactContentLocator locator, int maxBytes) {
            calls.incrementAndGet();
            return new byte[0];
        }
    }
}
