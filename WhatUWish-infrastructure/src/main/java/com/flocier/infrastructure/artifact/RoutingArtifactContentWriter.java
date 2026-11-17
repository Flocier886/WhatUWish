package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 按调用方明确声明的 storageType 路由，不把上传隐式落到任意存储后端。 */
@Component
public class RoutingArtifactContentWriter implements ArtifactContentWriter, ArtifactContentRemover {
    private final Map<String, ArtifactContentWriterBackend> backends;

    public RoutingArtifactContentWriter(List<ArtifactContentWriterBackend> values) {
        Map<String, ArtifactContentWriterBackend> registered = new LinkedHashMap<>();
        for (ArtifactContentWriterBackend backend : values) {
            for (String storageType : backend.storageTypes()) {
                if (registered.putIfAbsent(storageType, backend) != null) {
                    throw new IllegalStateException("duplicate Artifact content writer: " + storageType);
                }
            }
        }
        this.backends = Map.copyOf(registered);
    }

    @Override
    public ArtifactStoredContent write(ArtifactContentWriteRequest request) {
        if (request == null || request.getStorageType() == null || request.getStorageType().isBlank()) {
            throw new IllegalArgumentException("Artifact write storage type is required");
        }
        ArtifactContentWriterBackend backend = backends.get(request.getStorageType());
        if (backend == null) throw new IllegalArgumentException("Artifact write storage type is unavailable");
        return backend.write(request);
    }

    /** 按 locator 的 storageType 找到原写入后端，避免补偿时误删其他存储对象。 */
    @Override
    public void remove(ArtifactContentLocator locator) {
        if (locator == null || locator.getStorageType() == null) {
            throw new IllegalArgumentException("Artifact remove storage type is required");
        }
        ArtifactContentWriterBackend backend = backends.get(locator.getStorageType());
        if (backend == null) throw new IllegalArgumentException("Artifact remove storage type is unavailable");
        backend.remove(locator);
    }
}
