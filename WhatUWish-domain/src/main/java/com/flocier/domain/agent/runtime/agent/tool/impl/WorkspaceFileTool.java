package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.AgentRunMode;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.file.WorkspaceArtifactRegistrationService;
import com.flocier.domain.agent.runtime.memory.review.SkillReviewFiles;
import com.flocier.domain.agent.runtime.skill.SkillVirtualPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 面向 Agent 的 workspace_* 工具组，参考 master 的文件发现、读取和编辑语义。
 * skills/ 虚拟路径直达技能库，其余路径作用于当前会话的 inputs/ 与 outputs/。
 */
public class WorkspaceFileTool implements BaseTool {
    private static final int MAX_READ_CHARS = 12000;
    private static final int MAX_LIST = 200;
    private static final int MAX_RESULTS = 100;
    private static final int MAX_WRITE_CHARS = 200000;
    private static final int MAX_SCAN = 5000;
    private final String name;
    private final RunWorkspaceService workspace;
    private final WorkspaceArtifactRegistrationService registration;
    private final SkillVirtualPaths skillPaths;
    private final SkillReviewFiles reviewFiles;
    private final WorkspaceReadStateStore readStates = new WorkspaceReadStateStore();

    public WorkspaceFileTool(String name, RunWorkspaceService workspace,
                             WorkspaceArtifactRegistrationService registration) {
        this(name, workspace, registration, null, null);
    }

    public WorkspaceFileTool(String name, RunWorkspaceService workspace,
                             WorkspaceArtifactRegistrationService registration,
                             SkillVirtualPaths skillPaths, SkillReviewFiles reviewFiles) {
        this.name = name;
        this.workspace = workspace;
        this.registration = registration;
        this.skillPaths = skillPaths;
        this.reviewFiles = reviewFiles;
    }

    @Override public String name() { return name; }

    @Override public String description() {
        String boundary = " 路径只用相对路径；skills/ 指向技能库，输入位于 inputs/，生成文件写在 outputs/。不要传宿主绝对路径。";
        return switch (name) {
            case "workspace_list" -> "列出会话工作区文件和目录，包括前几轮留下的文件；可用 path、max_depth 限定范围。" + boundary;
            case "workspace_glob" -> "按 glob 模式发现工作区文件，返回相对路径和截断状态。" + boundary;
            case "workspace_grep" -> "在工作区文本文件中搜索关键字或正则，返回路径、行号和匹配文本。" + boundary;
            case "workspace_read" -> "按 start_line、line_count 有界读取 UTF-8 文本，结果带行号和续读位置；同范围内容未变化时返回 unchanged，请复用先前结果。二进制文件请交给相应格式工具。" + boundary;
            case "workspace_write" -> "写入 UTF-8 文本；会话文件写入 outputs/ 并由 workspace_register 交付，skills/ 直接修改技能库。" + boundary;
            case "workspace_edit" -> "编辑已读取的文本文件；skills/ 直接修改技能库，输入文件首次编辑生成 outputs/ 工作副本。old_string 默认必须唯一。" + boundary;
            case "workspace_register" -> "把 outputs/ 中已有文件显式登记为可交付产物，支持文本和二进制文件。" + boundary;
            default -> name;
        };
    }

    /** 按工具名生成模型可见的参数合同，尤其限制 path 为工作区相对路径。 */
    @Override public Object params() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("path", field("string", "工作区相对路径，技能文件使用 skills/<name>/..."));
        switch (name) {
            case "workspace_list" -> fields.put("max_depth", field("integer", "最大目录深度，默认 2"));
            case "workspace_glob" -> fields.put("pattern", field("string", "glob 模式，例如 **/*.md"));
            case "workspace_grep" -> {
                fields.put("pattern", field("string", "关键字或正则表达式"));
                fields.put("regex", field("boolean", "是否启用正则，默认 false"));
                fields.put("case_sensitive", field("boolean", "是否区分大小写，默认 false"));
            }
            case "workspace_read" -> {
                fields.put("start_line", field("integer", "起始行，默认 1"));
                fields.put("line_count", field("integer", "读取行数，默认 120"));
            }
            case "workspace_write" -> fields.put("content", field("string", "要写入的 UTF-8 文本"));
            case "workspace_edit" -> {
                fields.put("old_string", field("string", "文件中必须存在的原文"));
                fields.put("new_string", field("string", "替换后的文本"));
                fields.put("replace_all", field("boolean", "是否替换所有匹配，默认 false"));
            }
            default -> { }
        }
        return Map.of("type", "object", "properties", fields,
                "required", switch (name) {
                    case "workspace_list" -> List.of();
                    case "workspace_glob", "workspace_grep" -> List.of("pattern");
                    case "workspace_write" -> List.of("path", "content");
                    case "workspace_edit" -> List.of("path", "old_string", "new_string");
                    default -> List.of("path");
                });
    }

    /** 调度具体文件操作，并把有用的路径、匹配、截断或错误信息返回给 ReAct。 */
    @Override public Object execute(Object input, AgentContext context) {
        try {
            if (!(input instanceof ToolInvokeRequest request)) throw new IllegalArgumentException("工具参数格式错误");
            Map<String, Object> args = request.getArguments() == null ? Map.of() : request.getArguments();
            Map<String, Object> result = switch (name) {
                case "workspace_list" -> list(context, args);
                case "workspace_glob" -> glob(context, args);
                case "workspace_grep" -> grep(context, args);
                case "workspace_read" -> read(context, args);
                case "workspace_write" -> write(context, args);
                case "workspace_edit" -> edit(context, args);
                case "workspace_register" -> registration.register(context, workspace.writable(context, required(args, "path")),
                        request.getToolCallId());
                default -> throw new IllegalArgumentException("未知工作区工具");
            };
            return ToolResultPayload.success(name + " completed", JSON.toJSONString(result), result);
        } catch (Exception failure) {
            String message = failure instanceof IllegalArgumentException ? failure.getMessage() : "文件操作失败，请检查路径、大小与文件状态";
            return ToolResultPayload.failure("workspace_error", message, name + " 失败：" + message);
        }
    }

    /** 有界列出目录及字节大小；truncated 表示仍可能有未展示条目。 */
    private Map<String, Object> list(AgentContext context, Map<String, Object> args) throws IOException {
        Path root = pathOrRoot(context, args);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("目录不存在");
        int depth = Math.max(1, Math.min(8, number(args, "max_depth", 2)));
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        try (var paths = Files.walk(root, depth)) {
            var iterator = paths.filter(path -> !path.equals(root) && !Files.isSymbolicLink(path)
                    && visible(context, path)).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (entries.size() >= MAX_LIST) { truncated = true; break; }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", relative(context, path));
                row.put("type", Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "DIR" : "FILE");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) row.put("bytes", Files.size(path));
                entries.add(row);
            }
        }
        return Map.of("path", relative(context, root), "entries", entries, "truncated", truncated);
    }

    /** 使用 glob 找文件，并对扫描数、结果数分别设上限。 */
    private Map<String, Object> glob(AgentContext context, Map<String, Object> args) throws IOException {
        Path root = pathOrRoot(context, args);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("目录不存在");
        Pattern pattern = globPattern(required(args, "pattern"));
        List<String> files = new ArrayList<>();
        boolean truncated = false;
        int scanned = 0;
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++scanned > MAX_SCAN) { truncated = true; break; }
                if (visible(context, path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && pattern.matcher(root.relativize(path).toString().replace('\\', '/')).matches()) {
                    if (files.size() >= MAX_RESULTS) { truncated = true; break; }
                    files.add(relative(context, path));
                }
            }
        }
        return Map.of("path", relative(context, root), "pattern", required(args, "pattern"),
                "files", files, "truncated", truncated);
    }

    /** 搜索 UTF-8 文本；二进制或超限文件计入 skippedFiles，不伪称已扫描完整目录。 */
    private Map<String, Object> grep(AgentContext context, Map<String, Object> args) throws IOException {
        Path root = pathOrRoot(context, args);
        String expression = required(args, "pattern");
        boolean regex = Boolean.parseBoolean(String.valueOf(args.getOrDefault("regex", false)));
        boolean caseSensitive = Boolean.parseBoolean(String.valueOf(args.getOrDefault("case_sensitive", false)));
        Pattern pattern = Pattern.compile(regex ? expression : Pattern.quote(expression),
                caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean truncated = false;
        int scanned = 0;
        int skipped = 0;
        try (var paths = Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ? Files.walk(root) : java.util.stream.Stream.of(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++scanned > MAX_SCAN) { truncated = true; break; }
                if (!visible(context, path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.size(path) > MAX_WRITE_CHARS * 4L) { skipped++; continue; }
                try { requireUtf8Text(path); }
                catch (IllegalArgumentException binary) { skipped++; continue; }
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    int lineNo = 0;
                    String line;
                    while ((line = boundedLine(reader)) != null) {
                        lineNo++;
                        if (line.length() >= MAX_READ_CHARS) { truncated = true; break; }
                        if (pattern.matcher(line).find()) {
                            if (matches.size() >= MAX_RESULTS) { truncated = true; break; }
                            matches.add(Map.of("path", relative(context, path), "line", lineNo, "text", line));
                        }
                    }
                }
                if (truncated) break;
            }
        }
        return Map.of("path", relative(context, root), "pattern", expression,
                "matches", matches, "truncated", truncated || skipped > 0, "skippedFiles", skipped);
    }

    /** 按行读取有限正文并返回续读位置；同范围内容未变时给模型 unchanged 提示。 */
    private Map<String, Object> read(AgentContext context, Map<String, Object> args) throws IOException {
        Path path = readable(context, required(args, "path"));
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("文件不存在");
        if (Files.size(path) > MAX_INPUT_READ_BYTES) throw new IllegalArgumentException("文件过大，请使用专用解析工具");
        requireUtf8Text(path);
        int start = Math.max(1, number(args, "start_line", 1));
        int count = Math.max(1, Math.min(2000, number(args, "line_count", 120)));
        if (!AgentRunMode.isReview(context) && readStates.unchanged(context, path, start, count)) {
            return Map.of("path", relative(context, path), "startLine", start,
                    "lineCount", count, "unchanged", true,
                    "message", "文件与上次同范围读取时相同，请使用本轮先前的 workspace_read 结果");
        }
        StringBuilder content = new StringBuilder();
        int lineNo = 0;
        boolean truncated = false;
        if (AgentRunMode.isReview(context) && isSkill(path)) {
            try { SkillReviewFiles.read(context, path); }
            catch (Exception failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = boundedLine(reader)) != null) {
                lineNo++;
                if (line.length() >= MAX_READ_CHARS) { truncated = true; break; }
                if (lineNo < start) continue;
                if ((long) lineNo >= (long) start + count || content.length() + line.length() + 24 > MAX_READ_CHARS) {
                    truncated = true; break;
                }
                content.append(lineNo).append(" | ").append(line).append('\n');
            }
        }
        readStates.mark(context, path, start, count);
        return Map.of("path", relative(context, path), "startLine", start,
                "content", content.toString(), "nextLine", truncated ? lineNo : 0, "truncated", truncated);
    }

    /** 只写 outputs/ 的 UTF-8 工作文件；写成功不等于已交付 Artifact。 */
    private Map<String, Object> write(AgentContext context, Map<String, Object> args) throws IOException {
        String requested = required(args, "path");
        Path path = writable(context, requested);
        if (AgentRunMode.isReview(context) && isSkill(path)) return reviewWrite(context, args, false);
        String content = String.valueOf(args.getOrDefault("content", ""));
        if (content.length() > MAX_WRITE_CHARS) throw new IllegalArgumentException("写入文本超过 200000 字符");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_INPUT_READ_BYTES) throw new IllegalArgumentException("写入文件过大");
        if (!isSkill(path)) workspace.checkWriteBudget(context, path, bytes.length);
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
        readStates.mark(context, path);
        if (isSkill(path)) return Map.of("path", relative(context, path), "bytes", bytes.length,
                "registered", false, "next", "已写入技能库；使用 skill_view 重新加载");
        return Map.of("path", relative(context, path), "bytes", bytes.length,
                "registered", false, "next", "调用 workspace_register 交付该文件");
    }

    /** 基于已读且未变更的文件做精确替换；默认要求匹配唯一，避免短字符串误改多处。 */
    private Map<String, Object> edit(AgentContext context, Map<String, Object> args) throws IOException {
        String requested = required(args, "path");
        boolean inputCopy = requested.startsWith("inputs/") && !requested.startsWith("inputs/artifacts/");
        Path path = inputCopy ? readable(context, requested) : writable(context, requested);
        if (AgentRunMode.isReview(context) && isSkill(path)) return reviewWrite(context, args, true);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_INPUT_READ_BYTES) {
            throw new IllegalArgumentException("可编辑文件不存在或过大");
        }
        requireUtf8Text(path);
        readStates.requireCurrent(context, path);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String oldText = required(args, "old_string");
        if (!args.containsKey("new_string")) throw new IllegalArgumentException("new_string 不能为空");
        String newText = String.valueOf(args.get("new_string"));
        if (oldText.equals(newText)) throw new IllegalArgumentException("替换前后文本不能相同");
        int occurrences = 0;
        for (int index = original.indexOf(oldText); index >= 0; index = original.indexOf(oldText, index + oldText.length())) occurrences++;
        if (occurrences == 0) throw new IllegalArgumentException("old_string 未在文件中找到");
        boolean all = Boolean.parseBoolean(String.valueOf(args.getOrDefault("replace_all", false)));
        if (!all && occurrences > 1) throw new IllegalArgumentException("old_string 匹配多处，请扩大上下文或使用 replace_all");
        String updated = all ? original.replace(oldText, newText)
                : original.substring(0, original.indexOf(oldText)) + newText
                + original.substring(original.indexOf(oldText) + oldText.length());
        if (updated.length() > MAX_WRITE_CHARS) throw new IllegalArgumentException("编辑后文件超过 200000 字符");
        byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
        if (inputCopy) {
            Path copy = workspace.writable(context, "outputs/" + path.getFileName());
            if (Files.exists(copy, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("该输入已有工作副本，请读取并编辑 outputs/" + path.getFileName());
            workspace.checkWriteBudget(context, copy, bytes.length);
            Files.write(copy, bytes, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
            path = copy;
        } else {
            if (!isSkill(path)) workspace.checkWriteBudget(context, path, bytes.length);
            Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        }
        readStates.mark(context, path);
        return Map.of("path", relative(context, path), "charsBefore", original.length(),
                "charsAfter", updated.length(), "replacements", all ? occurrences : 1, "registered", false);
    }

    private static final int MAX_INPUT_READ_BYTES = 32 * 1024 * 1024;
    private Map<String, Object> field(String type, String description) { return Map.of("type", type, "description", description); }
    private String required(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(key + " 不能为空");
        return String.valueOf(value);
    }
    private int number(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }
    private Path pathOrRoot(AgentContext context, Map<String, Object> args) {
        Object value = args.get("path");
        return value == null || String.valueOf(value).isBlank()
                ? AgentRunMode.isReview(context) ? skillPaths.root() : workspace.root(context)
                : readable(context, String.valueOf(value));
    }

    private Path readable(AgentContext context, String raw) {
        if (skillPaths != null) {
            var virtual = skillPaths.resolve(raw);
            if (virtual.isPresent()) return virtual.get();
        }
        if (AgentRunMode.isReview(context)) throw new IllegalArgumentException("复盘只允许访问 skills/ 路径");
        return workspace.readable(context, raw);
    }

    private Path writable(AgentContext context, String raw) {
        if (skillPaths != null) {
            var virtual = skillPaths.resolve(raw);
            if (virtual.isPresent()) {
                Path target = virtual.get();
                if (target.equals(skillPaths.root())) throw new IllegalArgumentException("必须指定 skill 包内文件");
                try { Files.createDirectories(target.getParent()); }
                catch (IOException failure) { throw new IllegalArgumentException("skill 目录不可写", failure); }
                return target;
            }
        }
        if (AgentRunMode.isReview(context)) throw new IllegalArgumentException("复盘只允许访问 skills/ 路径");
        return workspace.writable(context, raw);
    }

    private boolean isSkill(Path path) {
        return skillPaths != null && skillPaths.contains(path);
    }

    private String relative(AgentContext context, Path path) {
        return isSkill(path) ? skillPaths.visible(path) : workspace.relative(context, path);
    }

    private boolean visible(AgentContext context, Path path) {
        return isSkill(path) || workspace.visible(context, path);
    }

    private Map<String, Object> reviewWrite(AgentContext context, Map<String, Object> args, boolean edit) {
        if (reviewFiles == null) throw new IllegalArgumentException("Skill 复盘写入服务未就绪");
        String path = required(args, "path").replace('\\', '/');
        if (path.startsWith("./")) path = path.substring(2);
        String[] segments = path.split("/", 3);
        if (segments.length != 3 || !segments[0].equals("skills"))
            throw new IllegalArgumentException("复盘路径必须为 skills/<name>/<file>");
        Map<String, Object> delegated = new LinkedHashMap<>(args);
        delegated.put("skill_name", segments[1]);
        delegated.put("path", segments[2]);
        try {
            Map<String, Object> result = new LinkedHashMap<>(reviewFiles.write(context, delegated, edit));
            result.put("path", path);
            return result;
        } catch (Exception failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
    }
    private Pattern globPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char c = glob.charAt(index);
            if (c == '*') {
                boolean pair = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (pair) {
                    if (index + 2 < glob.length() && glob.charAt(index + 2) == '/') {
                        regex.append("(?:.*/)?"); index += 2;
                    } else { regex.append(".*"); index++; }
                }
                else regex.append("[^/]*");
            } else if (c == '?') regex.append("[^/]");
            else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        return Pattern.compile(regex.append('$').toString());
    }
    /** 单行过长时直接报错，避免把剩余片段错误标成下一行。 */
    private String boundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = reader.read();
            if (c < 0) return line.isEmpty() ? null : line.toString();
            if (c == '\n') return line.toString();
            if (c == 0) throw new IllegalArgumentException("二进制文件不能按文本读取");
            if (c != '\r') line.append((char) c);
            if (line.length() >= MAX_READ_CHARS) {
                throw new IllegalArgumentException("单行文本超过读取上限，无法按行续读");
            }
        }
    }

    /** 预检二进制和无效 UTF-8；格式解析工具应在后续能力包处理此类文件。 */
    private void requireUtf8Text(Path path) throws IOException {
        byte[] sample;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            sample = input.readNBytes(8192);
        }
        for (byte value : sample) {
            if (value == 0) throw new IllegalArgumentException("二进制文件不能按文本读取");
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder();
            var result = decoder.decode(java.nio.ByteBuffer.wrap(sample),
                    java.nio.CharBuffer.allocate(sample.length), false);
            if (result.isError()) result.throwException();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("文件不是有效 UTF-8 文本", exception);
        }
    }
}
