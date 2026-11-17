package com.flocier.domain.agent.runtime.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 材料工具的文件入口：模型只提交会话工作区相对路径或同会话可见 Artifact ID。 */
@Service
@RequiredArgsConstructor
public class MaterialFileAccessResolver {
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES = 20;
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("(!\\[[^\\]]*\\]\\()([^\\s)]+)([^)]*\\))");
    private static final Pattern FILE_UUID = Pattern.compile("(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})");
    private static final Set<String> PATH_KEYS = Set.of("file_path", "file_path_2", "source_path", "source_files", "merge_files");
    private static final Set<String> ARTIFACT_KEYS = Set.of("artifact", "left_artifact", "right_artifact");
    private static final Set<String> DATASET_KEYS = Set.of("data", "left_data", "right_data");
    private static final Set<String> OUTPUT_KEYS = Set.of("output_path", "output_dir");
    private static final Map<String, Set<String>> TOOL_SUFFIXES = Map.ofEntries(
            Map.entry("pdf_reader", Set.of(".pdf")),
            Map.entry("pdf_structure", Set.of(".pdf")),
            Map.entry("citation_extractor", Set.of(".pdf")),
            Map.entry("word_reader", Set.of(".doc", ".docx", ".docm")),
            Map.entry("excel_reader", Set.of(".xls", ".xlsx", ".xlsm")),
            Map.entry("csv_processor", Set.of(".csv", ".tsv")),
            Map.entry("html_processor", Set.of(".html", ".htm")),
            Map.entry("markdown_processor", Set.of(".md", ".markdown")),
            Map.entry("image_ocr", Set.of(".png", ".jpg", ".jpeg", ".bmp", ".webp", ".tif", ".tiff", ".gif")));

    private final RunWorkspaceService workspace;
    private final IArtifactLedgerRepository artifacts;
    private final ArtifactContentStore contentStore;

    /** 逐字段处理嵌套材料引用；不得让 Python 根据模型字符串猜测宿主路径或存储 key。 */
    public Prepared prepare(AgentContext context, String toolName, Map<String, Object> arguments) {
        workspace.root(context);
        if (context.getSessionId() == null || context.getSessionId().isBlank()
                || context.getAttribute("runId") == null) throw new IllegalArgumentException("材料运行上下文不完整");
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        List<DeliveredFile> files = new ArrayList<>();
        String operation = String.valueOf(input.getOrDefault("operation", "read")).toLowerCase();
        boolean mutateSource = "text_processor".equals(toolName)
                && Set.of("write", "append", "prepend", "insert", "replace", "transform", "join").contains(operation);
        mutateSource |= "markdown_processor".equals(toolName)
                && Set.of("write", "create", "append", "prepend", "insert_section",
                "replace_section", "delete_section", "format", "build_table",
                "build_list", "merge", "template").contains(operation);
        mutateSource |= "csv_processor".equals(toolName) && "write".equals(operation);
        Map<String, Object> prepared = new LinkedHashMap<>();
        Set<String> sourceIds = new LinkedHashSet<>();
        Set<String> sourcePaths = new LinkedHashSet<>();
        for (var entry : input.entrySet()) {
            String key = entry.getKey();
            if (isInternalKey(key)) throw new IllegalArgumentException("材料工具不接受内部存储定位参数");
            if (isDocGenImageGeneration(toolName) && Set.of("blocks", "slides", "cover").contains(key)) {
                prepared.put(key, mapDocGenImages(context, entry.getValue(), files));
                continue;
            }
            if ("document_generate".equals(toolName) && "content".equals(key)
                    && entry.getValue() instanceof String markdown) {
                prepared.put(key, mapDocGenMarkdownImages(context, markdown, files));
                continue;
            }
            if ("sourceArtifactIds".equals(key)) {
                if (!"code_execution".equals(toolName) && !"code_interpreter".equals(toolName))
                    throw new IllegalArgumentException("该工具不接受检索来源");
                if (!(entry.getValue() instanceof List<?> ids) || ids.size() > MAX_FILES)
                    throw new IllegalArgumentException("检索来源 ID 列表无效或超过上限");
                for (Object id : ids) {
                    if (!(id instanceof String text)
                            || !(text.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                            || text.matches("[A-Fa-f0-9-]{36}")) || !sourceIds.add(text))
                        throw new IllegalArgumentException("检索来源 ID 重复或格式无效");
                    String path = (String) mapFileField(context, key, text, files, false);
                    sourcePaths.add(path);
                }
                continue;
            }
            if ("csv_processor".equals(toolName) && "write".equals(operation)
                    && "file_path".equals(key) && !(entry.getValue() instanceof String value
                    && value.startsWith("outputs/")))
                throw new IllegalArgumentException("CSV 写入目标只能在 outputs/ 下");
            if ("data".equals(key) && ("chart_generator".equals(toolName)
                    || "excel_generator".equals(toolName))) {
                if (containsLocator(entry.getValue()))
                    throw new IllegalArgumentException("文档数据包含未经授权的存储定位信息");
                prepared.put(key, entry.getValue());
                continue;
            }
            if (PATH_KEYS.contains(key) || ARTIFACT_KEYS.contains(key) || OUTPUT_KEYS.contains(key)) {
                prepared.put(key, mapFileField(context, key, entry.getValue(), files,
                        "file_path".equals(key) && mutateSource));
            } else if ("datasets".equals(key) && entry.getValue() instanceof List<?> list) {
                List<Object> values = new ArrayList<>();
                for (Object item : list) values.add(mapDataset(context, item, files));
                prepared.put(key, values);
            } else if ("tables".equals(key) && entry.getValue() instanceof Map<?, ?> map) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (var item : map.entrySet()) values.put(String.valueOf(item.getKey()), mapDataset(context, item.getValue(), files));
                prepared.put(key, values);
            } else if ("data".equals(key) && Set.of("text_processor", "csv_processor").contains(toolName)) {
                // 文本写入正文与 CSV 行记录是业务数据，不是 dataprep 的材料引用。
                prepared.put(key, entry.getValue());
            } else if (DATASET_KEYS.contains(key)) {
                prepared.put(key, mapDataset(context, entry.getValue(), files));
            } else if ("datasets".equals(key) || "tables".equals(key)) {
                throw new IllegalArgumentException("数据集参数格式无效");
            } else {
                if (containsLocator(entry.getValue()))
                    throw new IllegalArgumentException("材料参数包含未经授权的存储定位信息");
                prepared.put(key, entry.getValue());
            }
        }
        if ("code_execution".equals(toolName) || "code_interpreter".equals(toolName) || "bash".equals(toolName)) {
            Set<String> authorized = new LinkedHashSet<>(workspace.authorizedInputPaths(context));
            authorized.addAll(sourcePaths);
            prepared.put("__authorizedInputPaths", List.copyOf(authorized));
        }
        Set<String> allowedSuffixes = TOOL_SUFFIXES.get(toolName);
        if (allowedSuffixes != null) {
            for (DeliveredFile file : files) {
                String lowerName = file.fileName().toLowerCase(java.util.Locale.ROOT);
                if (allowedSuffixes.stream().noneMatch(lowerName::endsWith))
                    throw new IllegalArgumentException("材料类型与所选工具不匹配");
            }
        }
        return new Prepared(prepared, List.copyOf(files), workspace.root(context).toString());
    }

    private Object mapDataset(AgentContext context, Object value, List<DeliveredFile> files) {
        if (value == null) return null;
        if (value instanceof List<?> list) {
            if (list.size() <= 10_000 && list.stream().allMatch(item -> item instanceof Map<?, ?>))
                return value; // 行记录里的 uri/storageKey 可以是普通列名，不能误判成引用。
            throw new IllegalArgumentException("内联记录格式错误或超过一万行");
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.get("artifactId") instanceof String id)
                return mapFileField(context, "artifact", id, files, false);
            throw new IllegalArgumentException("数据集仅支持内联行记录、相对路径或 Artifact ID");
        }
        return mapFileField(context, "artifact", value, files, false);
    }

    private boolean containsLocator(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (Set.of("uri", "storageType", "storageKey", "storageRef", "artifactId").contains(key)
                        || containsLocator(entry.getValue())) return true;
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) if (containsLocator(item)) return true;
        }
        return false;
    }

    private boolean isDocGenImageGeneration(String toolName) {
        return "document_generate".equals(toolName) || "slides_generate".equals(toolName);
    }

    private Object mapDocGenImages(AgentContext context, Object value, List<DeliveredFile> files) {
        if (value instanceof List<?> list) {
            List<Object> mapped = new ArrayList<>();
            for (Object item : list) mapped.add(mapDocGenImages(context, item, files));
            return mapped;
        }
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (isInternalKey(key)) throw new IllegalArgumentException("材料工具不接受内部存储定位参数");
            if (("file_id".equals(key) || "image_file_id".equals(key)) && entry.getValue() instanceof String id) {
                String path = (String) mapFileField(context, "artifact", id, files, false);
                mapped.put("file_id".equals(key) ? "path" : "image_path", path);
            } else if (Set.of("path", "image_path", "logo_path").contains(key)
                    && entry.getValue() instanceof String path) {
                mapped.put(key, mapFileField(context, "source_path", path, files, false));
            } else {
                mapped.put(key, mapDocGenImages(context, entry.getValue(), files));
            }
        }
        return mapped;
    }

    private String mapDocGenMarkdownImages(AgentContext context, String markdown, List<DeliveredFile> files) {
        Matcher matcher = MARKDOWN_IMAGE.matcher(markdown);
        StringBuffer mapped = new StringBuffer();
        while (matcher.find()) {
            String source = matcher.group(2);
            Matcher id = FILE_UUID.matcher(source);
            if (!id.find() || (!source.equals(id.group()) && !source.contains("/files/"))) continue;
            String path = (String) mapFileField(context, "artifact", id.group(), files, false);
            matcher.appendReplacement(mapped, Matcher.quoteReplacement(matcher.group(1) + path + matcher.group(3)));
        }
        matcher.appendTail(mapped);
        return mapped.toString();
    }

    private Object mapFileField(AgentContext context, String key, Object value,
                                List<DeliveredFile> files, boolean mutateSource) {
        if (value == null) return null;
        if (value instanceof List<?> list) {
            List<Object> mapped = new ArrayList<>();
            for (Object item : list) mapped.add(mapFileField(context, key, item, files, mutateSource));
            return mapped;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.get("artifactId") instanceof String id) {
                return mapFileField(context, "artifact", id, files, false);
            }
            throw new IllegalArgumentException("材料引用只支持相对路径或 Artifact ID");
        }
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("材料文件引用无效");
        if (text.startsWith("@input/") || text.startsWith("@output/"))
            throw new IllegalArgumentException("材料工具不再接受旧文件令牌");
        if (OUTPUT_KEYS.contains(key) || "file_path".equals(key) && mutateSource && isNewFileOperation(context, text)) {
            workspace.writable(context, text);
            return outputPath(text);
        }
        if ("file_path".equals(key) && mutateSource && text.startsWith("outputs/")) {
            // 后续调用可以读取该文件，但写入内核始终操作隔离副本，不修改 Run 原文件。
            workspace.readable(context, text);
        }
        if (files.size() >= MAX_FILES) throw new IllegalArgumentException("材料文件数量超过上限");
        Path path;
        String name;
        if ((ARTIFACT_KEYS.contains(key) || "source_path".equals(key) || "file_path".equals(key)
                || "sourceArtifactIds".equals(key))
                && !text.startsWith("inputs/") && !text.startsWith("outputs/")) {
            if (!text.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                    && !text.matches("[A-Fa-f0-9-]{36}"))
                throw new IllegalArgumentException("Artifact ID 格式无效");
            ArtifactLedgerEntity artifact = artifacts.findByArtifactId(text).orElseThrow(() ->
                    new IllegalArgumentException("Artifact 不存在或无权访问"));
            if (!context.getSessionId().equals(artifact.getSessionId()) || Integer.valueOf(1).equals(artifact.getDeleted())
                    || !"visible".equalsIgnoreCase(artifact.getVisibility())
                    || ("sourceArtifactIds".equals(key) ? !isAnalysisSource(artifact)
                    : !"output".equalsIgnoreCase(artifact.getArtifactRole()))) {
                throw new IllegalArgumentException("Artifact 不属于当前可访问会话");
            }
            long size = artifact.getFileSize() == null ? -1 : artifact.getFileSize();
            if (size <= 0 || size > MAX_FILE_BYTES || artifact.getStorageType() == null || artifact.getStorageKey() == null)
                throw new IllegalArgumentException("Artifact 大小或存储描述不受支持");
            if ("file_path".equals(key) && mutateSource)
                throw new IllegalArgumentException("不能直接修改 Artifact；请先复制到本轮 outputs/");
            name = artifact.getFileName() == null ? "artifact.bin" : artifact.getFileName();
            String safeName = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
            String relative = "inputs/artifacts/" + artifact.getArtifactId() + "-" + safeName;
            path = workspace.readable(context, relative);
            try {
                Files.createDirectories(path.getParent());
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    Path staging = Files.createTempFile(path.getParent(), ".material-", ".part");
                    try {
                        try (InputStream source = contentStore.openDownload(new ArtifactContentLocator(
                                artifact.getStorageType(), artifact.getStorageKey(), null));
                             OutputStream target = Files.newOutputStream(staging)) {
                            copyBounded(source, target, size);
                        }
                        try { Files.move(staging, path); }
                        catch (java.nio.file.FileAlreadyExistsException concurrent) {
                            // 同一 Run 的另一工具已完成物化，下面统一核对目标大小。
                        }
                    } finally {
                        Files.deleteIfExists(staging);
                    }
                }
                if (Files.size(path) != size) throw new IOException("Artifact 大小不符");
            } catch (Exception failure) { throw new IllegalArgumentException("Artifact 内容不可读取", failure); }
        } else {
            if (!text.startsWith("inputs/") && !text.startsWith("outputs/"))
                throw new IllegalArgumentException("只接受本轮工作区相对路径");
            path = workspace.readable(context, text);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("材料文件不存在");
            try {
                long size = Files.size(path);
                if (size <= 0 || size > MAX_FILE_BYTES) throw new IllegalArgumentException("材料文件大小超限");
            } catch (IOException failure) { throw new IllegalArgumentException("材料文件不可读取", failure); }
            name = path.getFileName().toString();
            if (text.startsWith("inputs/") && !text.startsWith("inputs/artifacts/"))
                name = workspace.inputName(context, path);
        }
        try {
            long total = Files.size(path);
            for (DeliveredFile file : files) total += Files.size(workspace.readable(context, file.path()));
            if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("本次材料总大小超过上限");
        } catch (IOException failure) { throw new IllegalArgumentException("材料文件不可读取", failure); }
        String relative = workspace.relative(context, path);
        files.add(new DeliveredFile(name, relative));
        return relative;
    }

    private boolean isNewFileOperation(AgentContext context, String text) {
        if (!text.startsWith("outputs/")) return false;
        Path target = workspace.writable(context, text);
        return !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isAnalysisSource(ArtifactLedgerEntity artifact) {
        return ("source".equalsIgnoreCase(artifact.getArtifactRole())
                && "web_page".equalsIgnoreCase(artifact.getArtifactType())
                && "web_fetch".equalsIgnoreCase(artifact.getSourceType())
                && "web_fetch".equalsIgnoreCase(artifact.getSourceName()))
                || ("output".equalsIgnoreCase(artifact.getArtifactRole())
                && "research_source_package".equalsIgnoreCase(artifact.getArtifactType())
                && "deep_search".equalsIgnoreCase(artifact.getSourceType())
                && "deep_search_tool".equalsIgnoreCase(artifact.getSourceName()));
    }

    private String outputPath(String text) {
        if (text == null || !text.startsWith("outputs/") || text.contains("\\") || text.contains("..")
                || text.contains(":") || text.contains("//")) throw new IllegalArgumentException("生成文件只允许 outputs/ 相对路径");
        String relative = text.substring("outputs/".length());
        if (relative.isBlank() || relative.endsWith("/") || relative.startsWith("/"))
            throw new IllegalArgumentException("生成文件路径无效");
        return text;
    }

    private boolean isInternalKey(String key) {
        return Set.of("uri", "storageType", "storageKey", "storageRef", "workspace_root", "workspaceRoot",
                "request_id", "fileInfo", "file_store", "__authorizedInputPaths").contains(key);
    }

    private void copyBounded(InputStream source, OutputStream target, long expectedSize) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = source.read(buffer)) != -1) {
            total += count;
            if (total > expectedSize || total > MAX_FILE_BYTES) throw new IOException("材料读取大小不符");
            target.write(buffer, 0, count);
        }
        if (total != expectedSize) throw new IOException("材料读取大小不符");
    }

    public record DeliveredFile(String fileName, String path) { }
    public record Prepared(Map<String, Object> arguments, List<DeliveredFile> files, String workspaceRoot) { }
}
