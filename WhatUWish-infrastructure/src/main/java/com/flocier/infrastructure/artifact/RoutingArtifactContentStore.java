package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按 Ledger 的显式 storageType 精确选择 Backend。 */
@Component
public class RoutingArtifactContentStore implements ArtifactContentStore {
    private final Map<String, ArtifactContentStoreBackend> backends;

    public RoutingArtifactContentStore(List<ArtifactContentStoreBackend> backendList) {
        Map<String, ArtifactContentStoreBackend> registered = new LinkedHashMap<>();
        for (ArtifactContentStoreBackend backend : backendList) {
            for (String storageType : backend.storageTypes()) {
                if (storageType == null || storageType.isBlank()) {
                    throw new IllegalStateException("Artifact Backend 存储类型不能为空");
                }
                ArtifactContentStoreBackend previous = registered.putIfAbsent(storageType, backend);
                if (previous != null) {
                    throw new IllegalStateException("Artifact Backend 存储类型重复注册");
                }
            }
        }
        this.backends = Map.copyOf(registered);
    }

    @Override
    public ArtifactStoredContent inspect(ArtifactContentLocator locator) {
        return backend(locator).inspect(locator);
    }

    @Override
    public InputStream openDownload(ArtifactContentLocator locator) {
        return backend(locator).openDownload(locator);
    }

    @Override
    public byte[] readPreview(ArtifactContentLocator locator, int maxBytes) {
        return backend(locator).readPreview(locator, maxBytes);
    }

    private ArtifactContentStoreBackend backend(ArtifactContentLocator locator) {
        if (locator == null || locator.getStorageType() == null || locator.getStorageType().isBlank()) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
        }
        ArtifactContentStoreBackend backend = backends.get(locator.getStorageType());
        if (backend == null) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
        }
        return backend;
    }
}
