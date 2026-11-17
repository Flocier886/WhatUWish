package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.infrastructure.artifact.ArtifactS3Properties;
import com.flocier.infrastructure.artifact.S3ArtifactContentStoreBackend;
import org.junit.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class S3ArtifactContentStoreBackendTest {

    @Test
    public void shouldInspectReadBoundedPreviewAndStreamDownload() throws Exception {
        byte[] content = "# object artifact".getBytes(StandardCharsets.UTF_8);
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength((long) content.length).build());
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> stream(content));
        S3ArtifactContentStoreBackend backend = new S3ArtifactContentStoreBackend(client, properties());

        assertEquals(content.length, backend.inspect(locator()).getSize());
        assertArrayEquals(content, backend.readPreview(locator(), 1024));
        try (var input = backend.openDownload(locator())) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    @Test
    public void shouldRejectOversizedPreviewBeforeGetAndRejectInvalidKey() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(65L).build());
        S3ArtifactContentStoreBackend backend = new S3ArtifactContentStoreBackend(client, properties());

        assertEquals("PREVIEW_LIMIT", assertThrows(ArtifactContentStoreException.class,
                () -> backend.readPreview(locator(), 64)).getMessage());
        verify(client, never()).getObject(any(GetObjectRequest.class));

        ArtifactContentLocator invalid = new ArtifactContentLocator(
                ArtifactStorageType.S3, "private/secret.txt", "http://private/object");
        ArtifactContentStoreException invalidException = assertThrows(ArtifactContentStoreException.class,
                () -> backend.inspect(invalid));
        assertEquals("ARTIFACT_UNAVAILABLE", invalidException.getMessage());
        assertFalse(invalidException.getMessage().contains("private"));
    }

    @Test
    public void shouldMapMissingServiceAndAuthenticationErrorsWithoutDetails() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder()
                .statusCode(403).message("endpoint bucket object-key must-never-leak").build());
        S3ArtifactContentStoreBackend backend = new S3ArtifactContentStoreBackend(client, properties());

        ArtifactContentStoreException exception = assertThrows(ArtifactContentStoreException.class,
                () -> backend.inspect(locator()));

        assertEquals("ARTIFACT_UNAVAILABLE", exception.getMessage());
        assertFalse(exception.getMessage().contains("endpoint"));
        assertFalse(exception.getMessage().contains("object-key"));
        assertEquals(null, exception.getCause());
    }

    @Test
    public void shouldCloseSdkDownloadStreamWhenCallerCloses() throws Exception {
        byte[] content = "stream body".getBytes(StandardCharsets.UTF_8);
        TrackingInputStream underlying = new TrackingInputStream(content);
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(underlying)));
        S3ArtifactContentStoreBackend backend = new S3ArtifactContentStoreBackend(client, properties());

        try (var input = backend.openDownload(locator())) {
            assertArrayEquals(content, input.readAllBytes());
        }

        assertTrue(underlying.closed);
    }

    private ArtifactContentLocator locator() {
        return new ArtifactContentLocator(ArtifactStorageType.S3,
                "project/v1/artifacts/artifact-1/content", "must-not-be-used");
    }

    private ArtifactS3Properties properties() {
        return new ArtifactS3Properties("http://127.0.0.1:9000", "artifacts", "us-east-1",
                true, "access", "secret", "project", 5, 30);
    }

    private ResponseInputStream<GetObjectResponse> stream(byte[] content) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
    }

    private static class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
