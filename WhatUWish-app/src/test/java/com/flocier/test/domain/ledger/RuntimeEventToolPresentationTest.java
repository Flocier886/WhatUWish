package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.event.RuntimeEventToolPresentation;
import org.junit.Assert;
import org.junit.Test;

public class RuntimeEventToolPresentationTest {

    @Test
    public void shouldHideArtifactReadLocatorAndBodyFromPublicRuntimeProjection() {
        String arguments = "{\"artifactId\":\"artifact-1\",\"storageType\":\"s3_object_storage\","
                + "\"storageKey\":\"v1/artifacts/artifact-1/content\"}";
        String observation = "storageType: s3_object_storage\nstorageKey: v1/artifacts/artifact-1/content"
                + "\nINTERNAL_ARTIFACT_BODY";

        String publicArguments = RuntimeEventToolPresentation.arguments("artifact_read", arguments);
        String publicObservation = RuntimeEventToolPresentation.observation("artifact_read", observation);

        Assert.assertEquals("Artifact 的内部读取定位参数不在运行展示中提供。", publicArguments);
        Assert.assertEquals("Agent 已分段读取既有 Artifact；正文仅用于当前运行决策，不在运行记录中展示。", publicObservation);
        assertNoInternalLocator(publicArguments + publicObservation);
        Assert.assertFalse(publicObservation.contains("INTERNAL_ARTIFACT_BODY"));
    }

    @Test
    public void shouldRedactStorageTypeFromOtherToolPresentation() {
        String projected = RuntimeEventToolPresentation.observation(
                "generic_tool", "storage_type: s3_object_storage\n用户可见摘要"
        );

        Assert.assertEquals("用户可见摘要", projected);
        assertNoInternalLocator(projected);
    }

    private static void assertNoInternalLocator(String value) {
        Assert.assertFalse(value.contains("storageType"));
        Assert.assertFalse(value.contains("storage_type"));
        Assert.assertFalse(value.contains("storageKey"));
        Assert.assertFalse(value.contains("v1/artifacts/"));
        Assert.assertFalse(value.contains("s3_object_storage"));
    }
}
