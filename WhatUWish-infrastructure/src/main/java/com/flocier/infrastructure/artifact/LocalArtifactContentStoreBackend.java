package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStorageType;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.Set;

/** Python Local Artifact storage 的受控只读 Backend。 */
@Component
public class LocalArtifactContentStoreBackend implements ArtifactContentStoreBackend, ArtifactContentWriterBackend {
    private final Path root;

    public LocalArtifactContentStoreBackend(@Value("${artifact.content.local-root:}") String configuredRoot) {
        String envRoot = System.getenv("AI_AGENT_ARTIFACT_STORAGE_DIR");
        String value = configuredRoot == null || configuredRoot.isBlank() ? envRoot : configuredRoot;
        this.root = (value == null || value.isBlank() ? Path.of("data", "artifact-storage") : Path.of(value))
                .toAbsolutePath().normalize();
    }

    @Override
    public Set<String> storageTypes() {
        return Set.of(ArtifactStorageType.LOCAL, ArtifactStorageType.LEGACY_FILE_REF);
    }

    @Override
    public ArtifactStoredContent inspect(ArtifactContentLocator locator) {
        Path path = resolve(locator);
        try {
            return new ArtifactStoredContent(Files.size(path));
        } catch (IOException exception) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE", exception);
        }
    }

    @Override
    public InputStream openDownload(ArtifactContentLocator locator) {
        Path path = resolve(locator);
        try {
            return Files.newInputStream(path);
        } catch (IOException exception) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE", exception);
        }
    }

    @Override
    public byte[] readPreview(ArtifactContentLocator locator, int maxBytes) {
        if (maxBytes <= 0) {
            throw new ArtifactContentStoreException("PREVIEW_LIMIT");
        }
        Path path = resolve(locator);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new ArtifactContentStoreException("PREVIEW_LIMIT");
            }
            return bytes;
        } catch (ArtifactContentStoreException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE", exception);
        }
    }

    @Override
    public ArtifactStoredContent write(ArtifactContentWriteRequest request) {
        if (request == null || request.getContent() == null) throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
        Path path = resolveWritePath(request.getStorageKey());
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            // 先写同目录临时文件，目标仅在完整写入后出现；已有目标不能被覆盖或误清理。
            temporary = Files.createTempFile(path.getParent(), ".artifact-write-", ".tmp");
            Files.write(temporary, request.getContent(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            long size = Files.size(temporary);
            if (size != request.getContent().length) throw new IOException("incomplete artifact write");
            Files.move(temporary, path);
            return new ArtifactStoredContent(size);
        } catch (IOException exception) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    /** 本地对象写入失败补偿；正常软移除不调用此方法，历史 Run 仍可能引用原字节。 */
    @Override
    public void remove(ArtifactContentLocator locator) {
        Path path = resolveWritePath(locator == null ? null : locator.getStorageKey());
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE", exception);
        }
    }

    private Path resolve(ArtifactContentLocator locator) {
        if (locator == null || locator.preferredKey() == null || locator.preferredKey().isBlank()) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
        }
        Path candidate = root.resolve(locator.preferredKey().replace('\\', '/')).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
        }
        return candidate;
    }

    private Path resolveWritePath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
        try {
            new ArtifactObjectKeyBuilder("").validateStorageKey(storageKey);
        } catch (IllegalArgumentException exception) {
            throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE", exception);
        }
        Path candidate = root.resolve(storageKey.replace('\\', '/')).normalize();
        if (!candidate.startsWith(root)) throw new ArtifactContentStoreException("ARTIFACT_UNAVAILABLE");
        return candidate;
    }
}
