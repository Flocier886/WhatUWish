package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;

import java.net.URI;
import java.time.Duration;

/** S3-compatible Backend 的受控内部配置；该类不实现 toString，避免凭证误入日志。 */
public class ArtifactS3Properties {
    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final boolean pathStyle;
    private final String accessKey;
    private final String secretKey;
    private final ArtifactObjectKeyBuilder keyBuilder;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ArtifactS3Properties(String endpoint, String bucket, String region, boolean pathStyle,
                                String accessKey, String secretKey, String prefix,
                                long connectTimeoutSeconds, long readTimeoutSeconds) {
        this.endpoint = endpoint(endpoint);
        this.bucket = required(bucket, "bucket");
        this.region = required(region, "region");
        this.pathStyle = pathStyle;
        this.accessKey = required(accessKey, "access key");
        this.secretKey = required(secretKey, "secret key");
        this.keyBuilder = new ArtifactObjectKeyBuilder(prefix);
        this.connectTimeout = positiveDuration(connectTimeoutSeconds, "connect timeout");
        this.readTimeout = positiveDuration(readTimeoutSeconds, "read timeout");
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public String getRegion() {
        return region;
    }

    public boolean isPathStyle() {
        return pathStyle;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public ArtifactObjectKeyBuilder getKeyBuilder() {
        return keyBuilder;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    private URI endpoint(String value) {
        String endpoint = required(value, "endpoint");
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("S3 Artifact endpoint 配置无效");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("S3 Artifact endpoint 配置无效");
        }
        return uri;
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("S3 Artifact " + name + " 配置缺失");
        }
        return value.trim();
    }

    private Duration positiveDuration(long seconds, String name) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("S3 Artifact " + name + " 配置无效");
        }
        return Duration.ofSeconds(seconds);
    }
}
