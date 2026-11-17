package com.flocier.domain.agent.runtime.artifact.content;

import java.io.InputStream;

/** Artifact 物理内容的存储 Port。 */
public interface ArtifactContentStore {
    ArtifactStoredContent inspect(ArtifactContentLocator locator);

    InputStream openDownload(ArtifactContentLocator locator);

    byte[] readPreview(ArtifactContentLocator locator, int maxBytes);
}
