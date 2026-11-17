package com.flocier.test.domain.ledger;

import com.flocier.application.agent.file.ConversationFileService;
import com.flocier.application.agent.file.ConversationFileSessionService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.infrastructure.artifact.ArtifactS3Configuration;
import com.flocier.infrastructure.artifact.ArtifactS3Properties;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import com.flocier.infrastructure.artifact.RoutingArtifactContentWriter;
import com.flocier.infrastructure.artifact.S3ArtifactContentStoreBackend;
import com.flocier.infrastructure.persistent.memory.InMemoryConversationFileRepository;
import org.junit.Assume;
import org.junit.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MinioArtifactStorageIntegrationTest {

    @Test
    public void shouldKeepDistinctConversationUploadsAndOriginalBytesInMinio() throws Exception {
        Assume.assumeTrue("true".equalsIgnoreCase(System.getenv("AI_AGENT_ARTIFACT_S3_INTEGRATION")));
        ArtifactS3Properties properties = new ArtifactS3Properties(
                required("AI_AGENT_ARTIFACT_S3_ENDPOINT"), required("AI_AGENT_ARTIFACT_S3_BUCKET"),
                value("AI_AGENT_ARTIFACT_S3_REGION", "us-east-1"), true,
                required("AI_AGENT_ARTIFACT_S3_ACCESS_KEY"), required("AI_AGENT_ARTIFACT_S3_SECRET_KEY"),
                value("AI_AGENT_ARTIFACT_S3_PREFIX", "integration-java"), 5, 30);
        try (S3Client client = new ArtifactS3Configuration().artifactS3Client(properties)) {
            S3ArtifactContentStoreBackend backend = new S3ArtifactContentStoreBackend(client, properties);
            RoutingArtifactContentWriter writer = new RoutingArtifactContentWriter(List.of(backend));
            InMemoryConversationFileRepository repository = new InMemoryConversationFileRepository();
            ConversationFileSessionService sessions = new ConversationFileSessionService(repository, 1);
            ConversationFileService files = new ConversationFileService(sessions, repository, writer, writer,
                    new RoutingArtifactContentStore(List.of(backend)), ArtifactStorageType.S3,
                    value("AI_AGENT_ARTIFACT_S3_PREFIX", "integration-java"), 1024, 3, 1);
            var session = sessions.create();
            var uploaded = new java.util.ArrayList<String>();
            try {
                byte[][] originals = {{0, 1, -1, 10}, {7, 8, 9}};
                for (byte[] original : originals) {
                    var file = files.upload(session.sessionId(), "same.bin",
                            "application/octet-stream", original.length, new ByteArrayInputStream(original));
                    uploaded.add(file.fileId());
                    try (var stream = files.download(session.sessionId(), file.fileId())) {
                        assertArrayEquals(original, stream.readAllBytes());
                    }
                }
                assertEquals(2, files.list(session.sessionId()).size());
                org.junit.Assert.assertNotEquals(uploaded.get(0), uploaded.get(1));
            } finally {
                for (String fileId : uploaded) {
                    var record = repository.findFile(fileId).orElseThrow();
                    backend.remove(new ArtifactContentLocator(record.storageType(), record.storageKey(), null));
                }
            }
        }
    }

    @Test
    public void shouldPutHeadGetAndCloseAgainstExplicitMinioEnvironment() throws Exception {
        Assume.assumeTrue("true".equalsIgnoreCase(System.getenv("AI_AGENT_ARTIFACT_S3_INTEGRATION")));
        String endpoint = required("AI_AGENT_ARTIFACT_S3_ENDPOINT");
        String bucket = required("AI_AGENT_ARTIFACT_S3_BUCKET");
        String accessKey = required("AI_AGENT_ARTIFACT_S3_ACCESS_KEY");
        String secretKey = required("AI_AGENT_ARTIFACT_S3_SECRET_KEY");
        String region = value("AI_AGENT_ARTIFACT_S3_REGION", "us-east-1");
        String prefix = value("AI_AGENT_ARTIFACT_S3_PREFIX", "integration-java");
        boolean pathStyle = Boolean.parseBoolean(value("AI_AGENT_ARTIFACT_S3_PATH_STYLE", "true"));
        ArtifactS3Properties properties = new ArtifactS3Properties(endpoint, bucket, region, pathStyle,
                accessKey, secretKey, prefix, 5, 30);
        String artifactId = value("AI_AGENT_ARTIFACT_S3_PROBE_ID",
                "java-probe-" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT));
        String key = properties.getKeyBuilder().build(artifactId);
        byte[] content = ("artifact-minio-probe:" + artifactId).getBytes(StandardCharsets.UTF_8);

        try (S3Client client = new ArtifactS3Configuration().artifactS3Client(properties)) {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType("text/plain").metadata(java.util.Map.of(
                            "artifactid", artifactId,
                            "mimetype", "text/plain",
                            "sha256", org.apache.commons.codec.digest.DigestUtils.sha256Hex(content)
                    )).build(), RequestBody.fromBytes(content));
            S3ArtifactContentStoreBackend backend = new S3ArtifactContentStoreBackend(client, properties);
            ArtifactContentLocator locator = new ArtifactContentLocator(ArtifactStorageType.S3, key, key);
            try {
                assertEquals(content.length, backend.inspect(locator).getSize());
                assertArrayEquals(content, backend.readPreview(locator, 4096));
                try (var input = backend.openDownload(locator)) {
                    assertArrayEquals(content, input.readAllBytes());
                }
            } finally {
                backend.remove(locator);
            }
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        Assume.assumeTrue(name + " is required", value != null && !value.isBlank());
        return value.trim();
    }

    private String value(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
