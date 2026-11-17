package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** Java 文件服务使用的 S3-compatible 只读 Backend。 */
@Component
@ConditionalOnProperty(prefix = "artifact.content.s3", name = "enabled", havingValue = "true")
public class S3ArtifactContentStoreBackend implements ArtifactContentStoreBackend, ArtifactContentWriterBackend {
    private final S3Client client;
    private final ArtifactS3Properties properties;

    public S3ArtifactContentStoreBackend(S3Client client, ArtifactS3Properties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Set<String> storageTypes() {
        return Set.of(ArtifactStorageType.S3);
    }

    @Override
    public ArtifactStoredContent inspect(ArtifactContentLocator locator) {
        String key = storageKey(locator);
        try {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket()).key(key).build());
            return new ArtifactStoredContent(response.contentLength());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public InputStream openDownload(ArtifactContentLocator locator) {
        String key = storageKey(locator);
        try {
            return client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket()).key(key).build());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public byte[] readPreview(ArtifactContentLocator locator, int maxBytes) {
        if (maxBytes <= 0) {
            throw new ArtifactContentStoreException("PREVIEW_LIMIT");
        }
        ArtifactStoredContent stored = inspect(locator);
        if (stored.getSize() > maxBytes) {
            throw new ArtifactContentStoreException("PREVIEW_LIMIT");
        }
        String key = storageKey(locator);
        try (ResponseInputStream<GetObjectResponse> input = client.getObject(GetObjectRequest.builder()
                .bucket(properties.getBucket()).key(key).range("bytes=0-" + maxBytes).build())) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new ArtifactContentStoreException("PREVIEW_LIMIT");
            }
            return bytes;
        } catch (ArtifactContentStoreException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public ArtifactStoredContent write(ArtifactContentWriteRequest request) {
        if (request == null || request.getContent() == null) throw unavailable();
        String key = storageKey(new ArtifactContentLocator(request.getStorageType(), request.getStorageKey(), null));
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(request.getMimeType())
                            .ifNoneMatch("*")
                            .build(),
                    RequestBody.fromBytes(request.getContent()));
            return new ArtifactStoredContent(request.getContent().length);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /** S3 对象写入失败补偿；目标 bucket 和 key 均由服务端配置与 locator 决定。 */
    @Override
    public void remove(ArtifactContentLocator locator) {
        String key = storageKey(locator);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private String storageKey(ArtifactContentLocator locator) {
        if (locator == null || locator.getStorageKey() == null || locator.getStorageKey().isBlank()) {
            throw unavailable();
        }
        try {
            return properties.getKeyBuilder().validateStorageKey(locator.getStorageKey());
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private ArtifactContentStoreException unavailable() {
        return new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
    }
}
