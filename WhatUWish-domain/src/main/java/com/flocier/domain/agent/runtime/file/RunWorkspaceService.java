package com.flocier.domain.agent.runtime.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一会话跨 Run 复用的工作区，参考 master WorkspaceService 与 SessionFileMaterializer。
 * inputs/ 保存从上传原件物化的副本，outputs/ 保存可继续编辑的工作文件。
 */
@Service
public class RunWorkspaceService {
    public static final String ROOT_ATTRIBUTE = "conversation.workspace.root";
    public static final String FILES_ATTRIBUTE = "conversation.workspace.files";
    @Value("${conversation.files.max-file-bytes:33554432}")
    private int maxInputBytes = 32 * 1024 * 1024;
    @Value("${conversation.files.workspace-max-file-bytes:33554432}")
    private long maxWorkspaceFileBytes = 32L * 1024 * 1024;
    @Value("${conversation.files.workspace-max-total-bytes:134217728}")
    private long maxWorkspaceTotalBytes = 128L * 1024 * 1024;
    @Value("${conversation.files.workspace-max-files:100}")
    private int maxWorkspaceFiles = 100;
    @Value("${conversation.files.session-lifetime-days:30}")
    private long sessionLifetimeDays = 30;

    private final ArtifactContentStore contentStore;
    private final ConversationFileRepository files;
    private final Path base;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    public RunWorkspaceService(ArtifactContentStore contentStore, ConversationFileRepository files,
                               @Value("${conversation.files.workspace-root:data/run-workspaces}") String root) {
        this.contentStore = contentStore;
        this.files = files;
        this.base = Path.of(root).toAbsolutePath().normalize();
    }

    /**
     * 复用会话目录并物化本轮选中的文件；已存在的输入只校验，不覆盖跨轮内容。
     * 准备失败只撤销本次新写入的文件，不删除既有会话目录。
     */
    public List<MaterializedFile> prepare(AgentContext context, List<ConversationFileRecord> selected) {
        String sessionId = context == null ? null : context.getSessionId();
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("会话身份缺失");
        Path root = sessionRoot(sessionId);
        if (!activeSessions.add(sessionId)) throw new IllegalStateException("该会话已有运行中的 Agent，请等待完成后重试");
        List<Path> created = new ArrayList<>();
        try {
            Files.createDirectories(base);
            Files.createDirectories(root.resolve("inputs"));
            Files.createDirectories(root.resolve("outputs"));
            // 上一次进程异常退出留下的 Artifact 缓存不属于会话工作文件。
            cleanup(root.resolve("inputs/artifacts"));
            context.setAttribute(ROOT_ATTRIBUTE, root);
            List<MaterializedFile> files = new ArrayList<>();
            for (ConversationFileRecord file : selected == null ? List.<ConversationFileRecord>of() : selected) {
                if (!sessionId.equals(file.sessionId()) || !file.ready())
                    throw new IllegalArgumentException("输入文件不属于当前可用会话");
                String relative = inputPath(file);
                Path target = resolve(root, relative, false);
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    try (InputStream input = contentStore.openDownload(new ArtifactContentLocator(
                            file.storageType(), file.storageKey(), null));
                         var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        created.add(target);
                        byte[] buffer = new byte[8192];
                        int read;
                        long total = 0;
                        while ((read = input.read(buffer)) != -1) {
                            total += read;
                            if (total > maxInputBytes || total > file.fileSize()) throw new IOException("input size mismatch");
                            output.write(buffer, 0, read);
                        }
                    }
                }
                long total = Files.size(target);
                if (total == 0 || total != file.fileSize() || !hash(target).equalsIgnoreCase(file.fileHash())) {
                    throw new IOException("input digest mismatch");
                }
                files.add(new MaterializedFile(file.fileId(), file.displayName(), file.mimeType(), total, relative));
            }
            List<MaterializedFile> snapshot = List.copyOf(files);
            context.setAttribute(FILES_ATTRIBUTE, snapshot);
            Files.setLastModifiedTime(root, FileTime.from(Instant.now()));
            return snapshot;
        } catch (Exception failure) {
            for (Path path : created) try { Files.deleteIfExists(path); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            context.setAttribute(ROOT_ATTRIBUTE, null);
            context.setAttribute(FILES_ATTRIBUTE, null);
            activeSessions.remove(sessionId);
            throw new IllegalStateException("本轮输入文件无法准备，请检查文件是否仍可用并重试", failure);
        }
    }

    /** 获取当前 Run 的工作区根；尚未准备或已经清理时不能继续使用。 */
    public Path root(AgentContext context) {
        Path root = context == null ? null : context.getAttribute(ROOT_ATTRIBUTE);
        if (root == null || !root.equals(sessionRoot(context.getSessionId()))
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("工作区未就绪");
        }
        return root;
    }

    /** 将 Agent 提供的相对路径解析为受控可读路径。 */
    public Path readable(AgentContext context, String path) {
        Path root = root(context);
        Path target = resolve(root, path, false);
        if (target.startsWith(root.resolve("inputs")) && !target.startsWith(root.resolve("inputs/artifacts"))
                && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !validInput(context.getSessionId(), target)) {
            throw new IllegalArgumentException("会话输入文件已移除或不可用");
        }
        return target;
    }

    /** 列举和搜索不得把尚未物理清理的已移除输入重新展示给 Agent。 */
    public boolean visible(AgentContext context, Path path) {
        Path root = root(context);
        return !path.startsWith(root.resolve("inputs")) || path.startsWith(root.resolve("inputs/artifacts"))
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || validInput(context.getSessionId(), path);
    }

    public String inputName(AgentContext context, Path path) {
        return files.listFiles(context.getSessionId()).stream()
                .filter(file -> file.ready() && path.equals(sessionRoot(file.sessionId()).resolve(inputPath(file))))
                .map(ConversationFileRecord::displayName)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("会话输入文件已移除或不可用"));
    }

    /** 代码执行只接收当前会话仍为 READY 且已物化的上传输入。 */
    public List<String> authorizedInputPaths(AgentContext context) {
        Path root = root(context);
        return files.listFiles(context.getSessionId()).stream()
                .filter(ConversationFileRecord::ready)
                .map(this::inputPath)
                .filter(relative -> Files.isRegularFile(root.resolve(relative), LinkOption.NOFOLLOW_LINKS))
                .distinct().toList();
    }

    /** 只给提示词一个存在性信号，具体文件仍由 Agent 按需列举。 */
    public boolean hasFiles(AgentContext context) {
        Path root = root(context);
        try (var paths = Files.walk(root, 3)) {
            return paths.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && visible(context, path));
        } catch (IOException failure) {
            throw new IllegalStateException("会话工作区无法列举", failure);
        }
    }

    /** 写入只允许落在 outputs/，因此不能覆盖物化后的输入文件。 */
    public Path writable(AgentContext context, String path) {
        Path root = root(context);
        Path target = resolve(root, path, true);
        if (!target.startsWith(root.resolve("outputs")) || target.equals(root.resolve("outputs"))) {
            throw new IllegalArgumentException("只能写入 outputs/ 下的文件");
        }
        return target;
    }

    public String relative(AgentContext context, Path path) {
        return root(context).relativize(path).toString().replace('\\', '/');
    }

    /** Run 结束时只释放上下文和同会话占用，保留工作区供下一轮继续使用。 */
    public void finish(AgentContext context) {
        if (context == null) return;
        Path root = context.getAttribute(ROOT_ATTRIBUTE);
        if (root != null) {
            try {
                cleanup(root.resolve("inputs/artifacts"));
                Files.setLastModifiedTime(root, FileTime.from(Instant.now()));
            } catch (IOException failure) { throw new IllegalStateException("工作区状态更新失败", failure); }
            finally {
                context.setAttribute(ROOT_ATTRIBUTE, null);
                context.setAttribute(FILES_ATTRIBUTE, null);
                activeSessions.remove(context.getSessionId());
            }
        }
    }

    /** 软移除后的物理副本清理；读取边界仍会按会话文件状态重新核验。 */
    public void removeInput(ConversationFileRecord file) {
        Path root = sessionRoot(file.sessionId());
        try { Files.deleteIfExists(resolve(root, inputPath(file), false)); }
        catch (IOException failure) { throw new IllegalStateException("会话输入副本清理失败", failure); }
    }

    /** 定期回收过期且没有活动 Run 的目录；旧纯文本会话按闲置期回收。 */
    @Scheduled(fixedDelayString = "${conversation.files.workspace-cleanup-interval-ms:86400000}")
    public void cleanupExpiredSessions() {
        if (!Files.isDirectory(base)) return;
        Instant now = Instant.now();
        try (var entries = Files.list(base)) {
            for (Path root : entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = root.getFileName().toString();
                String sessionId = name.startsWith("session-") ? name.substring(8) : null;
                if (sessionId == null || !activeSessions.add(sessionId)) continue;
                try {
                    var session = files.findSession(sessionId);
                    boolean expired = session.isPresent() ? session.get().expiresAt().isBefore(now)
                            : Files.getLastModifiedTime(root).toInstant().plus(Duration.ofDays(sessionLifetimeDays)).isBefore(now);
                    if (expired) cleanup(root);
                } catch (Exception failure) {
                    // 单个损坏目录不得阻断其余过期会话的回收；下一轮调度会重试。
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("会话工作区回收失败, root={}", root, failure);
                } finally {
                    activeSessions.remove(sessionId);
                }
            }
        } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("会话工作区扫描失败, root={}", base, failure);
        }
    }

    /** 按新文件大小重算 outputs/ 的文件数与总容量；覆盖旧文件时不重复计入旧字节。 */
    public void checkWriteBudget(AgentContext context, Path target, long newBytes) throws IOException {
        if (newBytes < 0 || newBytes > maxWorkspaceFileBytes) {
            throw new IllegalArgumentException("单个工作区文件超过配置上限");
        }
        Path outputs = root(context).resolve("outputs");
        long bytes = 0;
        int files = 0;
        try (var paths = Files.walk(outputs)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                files++;
                if (!path.equals(target)) bytes += Files.size(path);
            }
        }
        if ((!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && files >= maxWorkspaceFiles)
                || bytes + newBytes > maxWorkspaceTotalBytes) {
            throw new IllegalArgumentException("工作区文件数或总容量已达到上限");
        }
    }

    /** 同时拒绝绝对路径、父目录越界及路径各段的符号链接，避免实际访问逃出 Run 根目录。 */
    private Path resolve(Path root, String path, boolean createParent) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path 不能为空");
        Path relative = Path.of(path.replace('\\', '/'));
        if (relative.isAbsolute()) throw new IllegalArgumentException("只接受工作区相对路径");
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("路径超出工作区");
        try {
            Path current = root;
            for (Path segment : root.relativize(target)) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("符号链接路径不可访问");
            }
            if (createParent) Files.createDirectories(target.getParent());
            return target;
        } catch (IOException failure) {
            throw new IllegalArgumentException("工作区路径不可用", failure);
        }
    }

    private String safeName(String value) {
        String name = value == null ? "attachment.bin" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) name = "attachment.bin";
        return name.length() <= 180 ? name : name.substring(0, 180);
    }

    private Path sessionRoot(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("会话身份无效");
        return base.resolve("session-" + sessionId);
    }

    private String inputPath(ConversationFileRecord file) {
        return "inputs/" + file.fileId() + "-" + safeName(file.displayName());
    }

    private boolean validInput(String sessionId, Path path) {
        return files.listFiles(sessionId).stream().anyMatch(file -> file.ready()
                && path.equals(sessionRoot(sessionId).resolve(inputPath(file))));
    }

    private String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void cleanup(Path root) {
        if (!root.startsWith(base) || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException failure) { throw new IllegalStateException("工作区清理失败", failure); }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("工作区清理失败", failure);
        }
    }

    /** 仅保留模型可见的安全元数据与相对路径，不暴露内容存储 locator。 */
    public record MaterializedFile(String fileId, String name, String mimeType, long size, String path) { }
}
