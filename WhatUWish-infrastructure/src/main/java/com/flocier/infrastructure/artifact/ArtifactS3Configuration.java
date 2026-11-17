package com.flocier.infrastructure.artifact;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@ConditionalOnProperty(prefix = "artifact.content.s3", name = "enabled", havingValue = "true")
public class ArtifactS3Configuration {

    @Bean
    public ArtifactS3Properties artifactS3Properties(
            @Value("${artifact.content.s3.endpoint:}") String endpoint,
            @Value("${artifact.content.s3.bucket:}") String bucket,
            @Value("${artifact.content.s3.region:us-east-1}") String region,
            @Value("${artifact.content.s3.path-style:true}") boolean pathStyle,
            @Value("${artifact.content.s3.access-key:}") String accessKey,
            @Value("${artifact.content.s3.secret-key:}") String secretKey,
            @Value("${artifact.content.s3.prefix:}") String prefix,
            @Value("${artifact.content.s3.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${artifact.content.s3.read-timeout-seconds:30}") long readTimeoutSeconds) {
        return new ArtifactS3Properties(endpoint, bucket, region, pathStyle, accessKey, secretKey, prefix,
                connectTimeoutSeconds, readTimeoutSeconds);
    }

    @Bean(destroyMethod = "close")
    public S3Client artifactS3Client(ArtifactS3Properties properties) {
        return S3Client.builder()
                .endpointOverride(properties.getEndpoint())
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyle()).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.getConnectTimeout())
                        .socketTimeout(properties.getReadTimeout()))
                .build();
    }
}
