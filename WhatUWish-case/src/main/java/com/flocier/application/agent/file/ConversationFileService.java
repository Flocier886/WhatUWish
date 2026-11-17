package com.flocier.application.agent.file;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStoreException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewPolicy;
import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import com.flocier.domain.agent.runtime.file.ConversationFileRepository;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 用户文件的应用入口：管理 Run 创建前的上传事实、会话内选择与受控内容访问。
 * 文件字节交给 Artifact Content Port，文件身份和状态由会话文件仓储保存；两者不等同于 Run 的输入 Artifact。
 */
@Service
@Slf4j
public class ConversationFileService {
    @Resource private RunWorkspaceService workspace;
    private final ConversationFileSessionService sessions;
    private final ConversationFileRepository repository;
    private final ArtifactContentWriter writer;
    private final ArtifactContentRemover remover;
    private final ArtifactContentStore store;
    private final String storageType;
    private final ArtifactObjectKeyBuilder keyBuilder;
    private final int maxFileBytes;
    private final int maxFilesPerSession;
    private final Semaphore uploads;

    public ConversationFileService(ConversationFileSessionService sessions, ConversationFileRepository repository,
                                   ArtifactContentWriter writer, ArtifactContentRemover remover, ArtifactContentStore store,
                                   @Value("${artifact.content.write-storage-type:local_artifact_storage}") String storageType,
                                   @Value("${artifact.content.write-prefix:}") String writePrefix,
                                   @Value("${conversation.files.max-file-bytes:33554432}") int maxFileBytes,
                                   @Value("${conversation.files.max-files-per-session:50}") int maxFilesPerSession,
                                   @Value("${conversation.files.max-concurrent-uploads:4}") int maxConcurrentUploads) {
        if (maxFileBytes < 1 || maxFileBytes > 32 * 1024 * 1024 || maxFilesPerSession < 1
                || maxConcurrentUploads < 1) throw new IllegalArgumentException("会话文件限制配置无效");
        this.sessions = sessions;
        this.repository = repository;
        this.writer = writer;
        this.remover = remover;
        this.store = store;
        this.storageType = storageType;
        this.keyBuilder = new ArtifactObjectKeyBuilder(writePrefix);
        this.maxFileBytes = maxFileBytes;
        this.maxFilesPerSession = maxFilesPerSession;
        this.uploads = new Semaphore(maxConcurrentUploads);
    }

    /**
     * 上传并建立 READY 文件。先用有界内存读取和校验原始字节，再预留会话名额、写内容、核验存储大小、登记元数据。
     * 写入或登记失败时尝试移除对象并释放名额；调用方只有拿到返回的 fileId 才可将文件选入 Run。
     */
    @Transactional
    public ConversationFileView upload(String sessionId, String originalName,
                                       String contentType, long declaredSize, InputStream input) throws IOException {
        sessions.requireAccess(sessionId);
        if (declaredSize <= 0) throw new ConversationFileAccessException("FILE_EMPTY");
        if (declaredSize > maxFileBytes) throw new ConversationFileAccessException("FILE_TOO_LARGE");
        if (!uploads.tryAcquire()) throw new ConversationFileAccessException("FILE_UPLOAD_BUSY");
        try {
            byte[] content = input.readNBytes(maxFileBytes + 1);
            if (content.length == 0) throw new ConversationFileAccessException("FILE_EMPTY");
            if (content.length > maxFileBytes) throw new ConversationFileAccessException("FILE_TOO_LARGE");
            if (content.length != declaredSize) throw new ConversationFileAccessException("FILE_SIZE_MISMATCH");
            String fileId = UUID.randomUUID().toString();
            String key = keyBuilder.build(fileId);
            String name = safeName(originalName);
            String mime = safeContentMime(normalizeMime(contentType), name, content);
            String hash = sha256(content);
            ArtifactContentLocator locator = new ArtifactContentLocator(storageType, key, null);
            if (!repository.reserveFileSlot(sessionId, maxFilesPerSession)) {
                throw new ConversationFileAccessException("FILE_COUNT_LIMIT");
            }
            try {
                long storedSize = writer.write(ArtifactContentWriteRequest.builder()
                        .storageType(storageType).storageKey(key).content(content).mimeType(mime).build()).getSize();
                if (storedSize != content.length || store.inspect(locator).getSize() != content.length) {
                    throw new ConversationFileAccessException("FILE_STORAGE_MISMATCH");
                }
                ConversationFileRecord record = new ConversationFileRecord(fileId, sessionId, name, mime,
                        content.length, hash, storageType, key, "READY", Instant.now(), null);
                repository.save(record);
                return view(record);
            } catch (RuntimeException failure) {
                try {
                    remover.remove(locator);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                try {
                    repository.releaseFileSlot(sessionId);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } finally {
            uploads.release();
        }
    }

    /** 只返回指定会话的安全展示字段，内部存储定位信息不进入响应。 */
    public List<ConversationFileView> list(String sessionId) {
        sessions.requireAccess(sessionId);
        return repository.listFiles(sessionId).stream().map(this::view).toList();
    }

    public ConversationFileView metadata(String sessionId, String fileId) {
        return view(requireOwned(sessionId, fileId));
    }

    /** 软移除会话文件并释放名额；历史 Run 已登记的输入事实不会随之删除。 */
    @Transactional
    public void remove(String sessionId, String fileId) {
        ConversationFileRecord file = requireOwned(sessionId, fileId);
        if ("REMOVED".equals(file.status())
                || !repository.changeStatus(fileId, file.status(), "REMOVED", Instant.now())) {
            throw new ConversationFileAccessException("FILE_NOT_READY");
        }
        repository.releaseFileSlot(sessionId);
        // 数据库状态先撤销访问；物理副本清理失败时后续工作区读取仍会按状态拒绝。
        try { if (workspace != null) workspace.removeInput(file); }
        catch (RuntimeException cleanup) { log.warn("会话输入副本清理失败, fileId={}", fileId, cleanup); }
    }

    public ConversationFileRecord requireReady(String sessionId, String fileId) {
        ConversationFileRecord file = requireOwned(sessionId, fileId);
        if (!file.ready()) throw new ConversationFileAccessException("FILE_NOT_READY");
        return file;
    }

    /**
     * 发送前按浏览器给出的顺序重新解析 fileId，拒绝重复、跨会话、非 READY 或内容已缺失的文件。
     * 返回的是服务端可信快照，浏览器不能提交 MIME、路径或 storageKey 代替它。
     */
    public List<ConversationFileRecord> resolveSelection(String sessionId, List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return List.of();
        if (fileIds.size() > maxFilesPerSession || fileIds.stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(fileIds).size() != fileIds.size()) {
            throw new ConversationFileAccessException("FILE_SELECTION_INVALID");
        }
        return fileIds.stream().map(id -> {
            ConversationFileRecord file = requireReady(sessionId, id);
            try {
                if (store.inspect(locator(file)).getSize() != file.fileSize()) {
                    repository.changeStatus(id, "READY", "UNAVAILABLE", null);
                    throw new ConversationFileAccessException("FILE_UNAVAILABLE");
                }
            } catch (ArtifactContentStoreException exception) {
                repository.changeStatus(id, "READY", "UNAVAILABLE", null);
                throw new ConversationFileAccessException("FILE_UNAVAILABLE");
            }
            return file;
        }).toList();
    }

    /** 校验会话归属和 READY 状态后打开原始字节流；内容缺失时撤销新选择资格。 */
    public InputStream download(String sessionId, String fileId) {
        ConversationFileRecord file = requireReady(sessionId, fileId);
        try {
            return store.openDownload(locator(file));
        } catch (ArtifactContentStoreException exception) {
            repository.changeStatus(fileId, "READY", "UNAVAILABLE", null);
            throw new ConversationFileAccessException("FILE_UNAVAILABLE");
        }
    }

    /** 仅对后端认定可预览的类型开放内容；图片走二进制流，文本受预览大小上限约束。 */
    public Preview preview(String sessionId, String fileId) {
        ConversationFileRecord file = requireReady(sessionId, fileId);
        ArtifactPreviewDescriptor descriptor = previewDescriptor(file);
        if (descriptor == null) throw new ConversationFileAccessException("FILE_PREVIEW_UNSUPPORTED");
        if (ArtifactPreviewPolicy.isBinaryPreview(descriptor)) {
            return new Preview(descriptor, file.mimeType(), download(sessionId, fileId));
        }
        try {
            byte[] bytes = store.readPreview(locator(file), (int) ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES);
            return new Preview(descriptor, "text/plain; charset=UTF-8", new java.io.ByteArrayInputStream(bytes));
        } catch (ArtifactContentStoreException exception) {
            repository.changeStatus(fileId, "READY", "UNAVAILABLE", null);
            throw new ConversationFileAccessException("FILE_UNAVAILABLE");
        }
    }

    /** 本地单用户边界：核对会话和 fileId 归属。 */
    private ConversationFileRecord requireOwned(String sessionId, String fileId) {
        sessions.requireAccess(sessionId);
        ConversationFileRecord file = repository.findFile(fileId)
                .orElseThrow(() -> new ConversationFileAccessException("FILE_NOT_FOUND"));
        if (!sessionId.equals(file.sessionId())) throw new ConversationFileAccessException("FILE_NOT_FOUND");
        return file;
    }

    private ConversationFileView view(ConversationFileRecord file) {
        return new ConversationFileView(file.fileId(), file.sessionId(), file.displayName(), file.mimeType(),
                file.fileSize(), file.status(), file.createdAt(), previewDescriptor(file), file.ready());
    }

    /** 生成版本化 Reader 合同；REMOVED/UNAVAILABLE 与不安全内容均没有预览描述。 */
    private ArtifactPreviewDescriptor previewDescriptor(ConversationFileRecord file) {
        if (!file.ready()) return null;
        ArtifactAccessState state = ArtifactAccessState.AVAILABLE;
        if (file.fileSize() <= ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isPlainText(file.mimeType())) {
            return ArtifactPreviewDescriptor.textV1();
        }
        if ("text/markdown".equals(file.mimeType()) || "text/x-markdown".equals(file.mimeType())) {
            return ArtifactPreviewPolicy.describe(state, file.mimeType(), file.displayName(), file.fileSize());
        }
        if (ArtifactPreviewPolicy.isPreviewableRasterImage(file.mimeType())) {
            return ArtifactPreviewPolicy.describe(state, file.mimeType(), file.displayName(), file.fileSize(),
                    ArtifactPreviewDescriptor.imageV1());
        }
        return null;
    }

    /** 对文本 UTF-8 和栅格图片签名做校验；声明与字节不符时降为下载型内容。 */
    private String safeContentMime(String mime, String name, byte[] content) {
        boolean markdown = "text/markdown".equals(mime) || "text/x-markdown".equals(mime)
                || ("application/octet-stream".equals(mime) && name.toLowerCase(Locale.ROOT).endsWith(".md"));
        if (ArtifactPreviewPolicy.isPlainText(mime) || markdown) {
            return ArtifactPreviewPolicy.isUtf8Text(content) ? (markdown ? "text/markdown" : mime)
                    : "application/octet-stream";
        }
        if (ArtifactPreviewPolicy.isPreviewableRasterImage(mime)
                && !ArtifactPreviewPolicy.matchesRasterSignature(mime, content)) {
            return "application/octet-stream";
        }
        return mime;
    }

    private ArtifactContentLocator locator(ConversationFileRecord file) {
        return new ArtifactContentLocator(file.storageType(), file.storageKey(), null);
    }

    private String safeName(String name) {
        String value = name == null ? "" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) return "attachment.bin";
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private String normalizeMime(String value) {
        if (value == null || value.isBlank()) return "application/octet-stream";
        String mime = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mime.length() <= 128 && mime.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? mime : "application/octet-stream";
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Preview(ArtifactPreviewDescriptor descriptor, String contentType, InputStream stream) {
    }
}
