package com.flocier.domain.agent.runtime.file;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewPolicy;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;

import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 对 Python 声明的工作区输出进行原字节复核，并写入正式内容存储。 */
public class WorkspaceOutputPersistence {
    private final ArtifactContentWriter writer;
    private final ArtifactContentRemover remover;
    private final String storageType;
    private final ArtifactObjectKeyBuilder keys;

    public WorkspaceOutputPersistence(ArtifactContentWriter writer, ArtifactContentRemover remover,
                                      String storageType, String prefix) {
        this.writer = writer;
        this.remover = remover;
        this.storageType = storageType;
        this.keys = new ArtifactObjectKeyBuilder(prefix);
    }

    public List<Map<String, Object>> persist(List<Map<String, Object>> outputs, String workspaceRoot,
                                               String toolName) throws Exception {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        try {
            for (Map<String, Object> output : outputs) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("材料处理已中断");
                descriptors.add(persistOne(output, workspaceRoot, toolName));
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("材料处理已中断");
            return descriptors;
        } catch (Exception failure) {
            try { discard(descriptors); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** 仅用于登记开始前撤回本轮新写入的对象。 */
    public void discard(List<Map<String, Object>> descriptors) throws Exception {
        Exception failure = null;
        for (Map<String, Object> descriptor : descriptors) {
            try { remover.remove(new ArtifactContentLocator(storageType,
                    String.valueOf(descriptor.get("storageKey")), null)); }
            catch (Exception cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    private Map<String, Object> persistOne(Map<String, Object> output, String workspaceRoot,
                                            String toolName) throws Exception {
        if (output == null) throw new IllegalArgumentException("材料输出描述无效");
        String id = String.valueOf(output.get("artifactId"));
        String fileName = String.valueOf(output.get("fileName"));
        String relative = String.valueOf(output.get("relativePath"));
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || !relative.startsWith("outputs/") || relative.contains("\\"))
            throw new IllegalArgumentException("材料输出描述无效");
        Path root = Path.of(workspaceRoot).toAbsolutePath().normalize();
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root.resolve("outputs")) || !path.getFileName().toString().equals(fileName))
            throw new IllegalArgumentException("材料输出路径无效");
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("材料输出不能包含符号链接");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("材料输出文件不存在");
        long size = Files.size(path);
        if (size == 0 || size > 32L * 1024 * 1024) throw new IllegalArgumentException("材料输出文件大小无效");
        byte[] content = Files.readAllBytes(path);
        if (content.length == 0 || content.length > 32 * 1024 * 1024)
            throw new IllegalArgumentException("材料输出文件大小无效");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        if (("code_execution".equals(toolName) || "code_interpreter".equals(toolName) || "bash".equals(toolName))
                && !hash.equals(output.get("sha256")))
            throw new IllegalArgumentException("代码执行输出在采集后发生变化");
        String key = keys.build(id);
        String mime = URLConnection.guessContentTypeFromName(fileName);
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".csv")) mime = "text/csv";
        if (lowerName.endsWith(".docx")) mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lowerName.endsWith(".xlsx")) mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (mime == null) mime = "application/octet-stream";
        ArtifactContentLocator locator = new ArtifactContentLocator(storageType, key, null);
        boolean written = false;
        try {
            ArtifactStoredContent stored = writer.write(ArtifactContentWriteRequest.builder()
                    .storageType(storageType).storageKey(key).content(content).mimeType(mime).build());
            written = true;
            if (stored == null || stored.getSize() != content.length)
                throw new IllegalStateException("材料输出写入不完整");
        } catch (Exception failure) {
            if (written) {
                try { remover.remove(locator); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("artifactId", id);
        descriptor.put("name", fileName);
        descriptor.put("artifactType", "file");
        descriptor.put("artifactRole", "output");
        descriptor.put("visibility", "visible");
        descriptor.put("sourceType", "code_execution".equals(toolName) || "code_interpreter".equals(toolName)
                || "bash".equals(toolName)
                ? toolName : "material_tool");
        descriptor.put("sourceName", toolName);
        descriptor.put("fileName", fileName);
        descriptor.put("storageType", storageType);
        descriptor.put("storageKey", key);
        descriptor.put("storageRef", key);
        descriptor.put("contentSummary", toolName + " 生成文件");
        descriptor.put("mimeType", mime);
        descriptor.put("fileSize", content.length);
        descriptor.put("fileHash", hash);
        ArtifactPreviewDescriptor preview = null;
        if (ArtifactPreviewPolicy.isPreviewableRasterImage(mime)
                && ArtifactPreviewPolicy.matchesRasterSignature(mime, content))
            preview = ArtifactPreviewDescriptor.imageV1();
        else if (ArtifactPreviewPolicy.isPdf(mime, fileName)
                && ArtifactPreviewPolicy.matchesPdfSignature(content))
            preview = ArtifactPreviewDescriptor.pdfV1();
        else if (ArtifactPreviewPolicy.isDocx(mime, fileName)
                && ArtifactPreviewPolicy.matchesOfficeSignature(content))
            preview = ArtifactPreviewDescriptor.docxV1();
        else if (ArtifactPreviewPolicy.isXlsx(mime, fileName)
                && ArtifactPreviewPolicy.matchesOfficeSignature(content))
            preview = ArtifactPreviewDescriptor.spreadsheetV1();
        else if (ArtifactPreviewPolicy.isCsv(mime, fileName)
                && content.length <= ArtifactPreviewPolicy.CSV_PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isUtf8Text(content))
            preview = ArtifactPreviewDescriptor.spreadsheetV1();
        else if ("text/markdown".equals(mime)
                && content.length <= ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isUtf8Text(content))
            preview = ArtifactPreviewDescriptor.markdownV1();
        else if ("text/html".equals(mime)
                && content.length <= ArtifactPreviewPolicy.HTML_PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isUtf8Text(content))
            preview = ArtifactPreviewDescriptor.htmlV1();
        else if (ArtifactPreviewPolicy.isPlainText(mime)
                && content.length <= ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isUtf8Text(content))
            preview = ArtifactPreviewDescriptor.textV1();
        if (preview != null)
            descriptor.put("metadata", Map.of("preview", Map.of("kind", preview.getKind(),
                    "schemaVersion", ArtifactPreviewDescriptor.V1)));
        return descriptor;
    }
}
