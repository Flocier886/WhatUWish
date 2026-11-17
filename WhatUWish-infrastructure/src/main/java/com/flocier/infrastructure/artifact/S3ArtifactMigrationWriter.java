package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationWriteException;
import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只为显式迁移提供对象写入，不参与普通用户文件访问。 */
@Component
@ConditionalOnProperty(prefix = "artifact.content.s3", name = "enabled", havingValue = "true")
public class S3ArtifactMigrationWriter implements ArtifactMigrationWriter {
    private final S3Client client;
    private final ArtifactS3Properties properties;

    public S3ArtifactMigrationWriter(S3Client client, ArtifactS3Properties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String targetKey(String artifactId) {
        return properties.getKeyBuilder().build(artifactId);
    }

    @Override
    public void upload(String artifactId, String targetKey, String mimeType, String sha256,
                       long contentLength, InputStream content) {
        String key;
        try {
            key = properties.getKeyBuilder().validateStorageKey(targetKey);
        } catch (IllegalArgumentException exception) {
            throw new ArtifactMigrationWriteException("target_key_invalid");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("artifactid", artifactId);
        metadata.put("mimetype", mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType);
        metadata.put("sha256", sha256);
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(properties.getBucket()).key(key)
                            .contentType(metadata.get("mimetype")).metadata(metadata).build(),
                    RequestBody.fromInputStream(content, contentLength));
            HeadObjectResponse head = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket()).key(key).build());
            Map<String, String> actual = head.metadata() == null ? Map.of() : head.metadata();
            if (head.contentLength() != contentLength
                    || !artifactId.equals(actual.get("artifactid"))
                    || !sha256.equalsIgnoreCase(actual.getOrDefault("sha256", ""))) {
                throw new ArtifactMigrationWriteException("target_head_validation_failed");
            }
        } catch (ArtifactMigrationWriteException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ArtifactMigrationWriteException("target_upload_failed");
        }
    }
}
