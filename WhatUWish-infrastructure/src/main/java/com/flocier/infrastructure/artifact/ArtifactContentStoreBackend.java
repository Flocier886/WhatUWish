package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;

import java.io.InputStream;
import java.util.Set;

/** 具体物理存储的内部 SPI，不承担账本、授权或公开协议职责。 */
public interface ArtifactContentStoreBackend {
    Set<String> storageTypes();

    ArtifactStoredContent inspect(ArtifactContentLocator locator);

    InputStream openDownload(ArtifactContentLocator locator);

    byte[] readPreview(ArtifactContentLocator locator, int maxBytes);
}
