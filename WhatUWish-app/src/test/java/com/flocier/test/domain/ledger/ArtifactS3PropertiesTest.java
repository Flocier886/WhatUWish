package com.flocier.test.domain.ledger;

import com.flocier.infrastructure.artifact.ArtifactS3Properties;
import org.junit.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ArtifactS3PropertiesTest {

    @Test
    public void shouldKeepConnectionContractWithoutRenderingSecrets() {
        String secret = "must-never-be-rendered";
        ArtifactS3Properties properties = new ArtifactS3Properties(
                "http://127.0.0.1:9000", "artifacts", "us-east-1", true,
                "access", secret, "project", 5, 30);

        assertEquals("http://127.0.0.1:9000", properties.getEndpoint().toString());
        assertEquals("project/v1/artifacts/artifact-1/content", properties.getKeyBuilder().build("artifact-1"));
        assertFalse(properties.toString().contains(secret));
    }

    @Test
    public void shouldFailClosedForIncompleteOrInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactS3Properties(
                "", "bucket", "us-east-1", true, "access", "secret", "", 5, 30));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactS3Properties(
                "http://localhost:9000", "", "us-east-1", true, "access", "secret", "", 5, 30));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactS3Properties(
                "ftp://localhost:9000", "bucket", "us-east-1", true, "access", "secret", "", 5, 30));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactS3Properties(
                "http://localhost:9000", "bucket", "us-east-1", true, "access", "secret", "", 0, 30));
    }
}
