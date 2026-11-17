package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;
import org.junit.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ArtifactObjectKeyBuilderTest {

    @Test
    public void shouldBuildVersionedArtifactIdFirstKey() {
        assertEquals("v1/artifacts/artifact_01/content",
                new ArtifactObjectKeyBuilder("").build("artifact_01"));
        assertEquals("project/runtime/v1/artifacts/artifact-02/content",
                new ArtifactObjectKeyBuilder("project/runtime").build("artifact-02"));
    }

    @Test
    public void shouldRejectPathsFileNamesAndInvalidPrefix() {
        ArtifactObjectKeyBuilder builder = new ArtifactObjectKeyBuilder("project/runtime");
        for (String artifactId : new String[]{null, "", "../id", "folder/id", "report.md", "/absolute", "中文"}) {
            assertThrows(IllegalArgumentException.class, () -> builder.build(artifactId));
        }
        for (String prefix : new String[]{"/root", "root/", "root//child", "root/../child", "root\\child"}) {
            assertThrows(IllegalArgumentException.class, () -> new ArtifactObjectKeyBuilder(prefix));
        }
    }

    @Test
    public void shouldValidateOnlyPublishedKeyShape() {
        ArtifactObjectKeyBuilder builder = new ArtifactObjectKeyBuilder("project");
        String key = builder.build("artifact-1");

        assertEquals(key, builder.validateStorageKey(key));
        for (String invalid : new String[]{
                "project/v1/artifacts/artifact-1/report.md",
                "project/v1/artifacts/artifact-1/content/extra",
                "other/v1/artifacts/artifact-1/content",
                "project/session/run/artifact-1/content",
                "/project/v1/artifacts/artifact-1/content"
        }) {
            assertThrows(IllegalArgumentException.class, () -> builder.validateStorageKey(invalid));
        }
    }
}
