package com.flocier.domain.agent.runtime.memory.review;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.AgentRunMode;
import com.flocier.domain.agent.runtime.skill.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 搬用 master Workspace write/edit 的文本语义，仅保留 Skill 库职责。 */
@Component
@RequiredArgsConstructor
public class SkillReviewFiles {
    private final SkillRegistry registry;
    private final SkillRuntimeOptions options;
    private final SkillPathGuard guard;
    /** 与 master Workspace 默认上限相同，避免后台一次生成过大文件。 */
    public static final int MAX_WRITE_CHARS = 200_000;

    /** 未注册包只在复盘中解析；普通工具仍须通过 Registry。 */
    public static SkillDefinition resolvePackage(String name, SkillRegistry registry, SkillRuntimeOptions options, SkillPathGuard guard) {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}"))
            throw new IllegalArgumentException("skill_name 必须是单层包名");
        Optional<SkillDefinition> existing = registry.findSkill(name);
        if (existing.isPresent()) return existing.get();
        List<String> roots = options.getDirectories();
        if (roots == null || roots.stream().noneMatch(s -> s != null && !s.isBlank()))
            throw new IllegalStateException("没有配置可写入的 Skill 根目录");
        Path root = SkillPathGuard.resolveConfiguredRoot(roots.stream().filter(s -> s != null && !s.isBlank()).findFirst().orElseThrow());
        Path path = guard.assertPathUnderRoot(root.resolve(name), root);
        return SkillDefinition.builder().name(name).basePath(path).build();
    }

    /** 完整文本摘要用于冲突检查，修改时间保留给读取版本诊断。 */
    public static SkillReviewState.ReadVersion readVersion(Path path, String content) throws Exception {
        return new SkillReviewState.ReadVersion(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))), Files.getLastModifiedTime(path).toMillis());
    }

    /** 在文件锁内读取完整内容并记录版本，展示仍由现有 read 工具限行限长。 */
    public static String read(AgentContext context, Path path) throws Exception {
        synchronized (SkillReviewState.FILE_LOCK) {
            context.getSkillReviewState().checkActive();
            String content = Files.readString(path, StandardCharsets.UTF_8);
            context.getSkillReviewState().remember(path, readVersion(path, content));
            return content;
        }
    }

    /** 先检查版本再落盘；成功后刷新读取状态，允许下一次连续 edit。 */
    public Map<String, Object> write(AgentContext context, Map<String, Object> args, boolean edit) throws Exception {
        if (!AgentRunMode.isReview(context)) throw new IllegalArgumentException("Skill 写工具仅供后台复盘使用");
        synchronized (SkillReviewState.FILE_LOCK) {
            SkillReviewState state = context.getSkillReviewState();
            state.checkActive();
            String name = required(args, "skill_name");
            SkillDefinition skill = resolvePackage(name, registry, options, guard);
            String relative = required(args, "path");
            Path raw = Path.of(relative);
            if (raw.isAbsolute() || relative.contains(":") || relative.startsWith("\\")) throw new IllegalArgumentException("path 必须为包内相对路径");
            Path path = guard.assertPathUnderRoot(skill.getBasePath().resolve(raw), skill.getBasePath());
            // 已注册包也不能借助包根本身的链接逃离配置库。
            guard.assertPathAllowed(path, options.getDirectories().stream().filter(s -> s != null && !s.isBlank()).map(SkillPathGuard::resolveConfiguredRoot).toList());
            boolean existed = Files.exists(path);
            if (existed && !Files.isRegularFile(path)) throw new IllegalArgumentException("只支持文本文件");
            String before = existed ? Files.readString(path, StandardCharsets.UTF_8) : null;
            String updated;
            int replacements = 0;
            if (edit) {
                if (!existed) throw new IllegalArgumentException("edit 只支持已存在文件");
                requireUnchanged(state, path, before);
                String old = required(args, "old_string");
                String next = required(args, "new_string");
                if (old.isEmpty() || old.equals(next)) throw new IllegalArgumentException("old_string 不能为空且必须与 new_string 不同");
                for (int at = 0; (at = before.indexOf(old, at)) >= 0; at += old.length()) replacements++;
                if (replacements == 0) throw new IllegalArgumentException("old_string 未匹配，请重新读取且不要包含行号前缀");
                boolean all = Boolean.parseBoolean(String.valueOf(args.getOrDefault("replace_all", false)));
                if (!all && replacements != 1) throw new IllegalArgumentException("old_string 匹配多处，请补充上下文或设置 replace_all=true");
                updated = before.replace(old, next);
            } else {
                updated = required(args, "content");
                // 本项目对已有文件补充版本保护；master write 原版允许直接覆盖，新文件无需先读。
                if (existed) requireUnchanged(state, path, before);
            }
            if (updated.length() > MAX_WRITE_CHARS || (before != null && before.length() > MAX_WRITE_CHARS))
                throw new IllegalArgumentException("Skill 文本超过写入上限 " + MAX_WRITE_CHARS);
            boolean changed = !Objects.equals(before, updated);
            if (changed) {
                state.checkActive();
                Files.createDirectories(path.getParent());
                guard.assertPathUnderRoot(path, skill.getBasePath());
                Files.writeString(path, updated, StandardCharsets.UTF_8);
                state.changed(path, skill.getName(), skill.getBasePath().relativize(path).toString().replace('\\', '/'), !existed);
            }
            state.remember(path, readVersion(path, updated));
            return Map.of("skillName", skill.getName(), "path", skill.getBasePath().relativize(path).toString().replace('\\', '/'),
                    "changeType", changed ? (existed ? "updated" : "created") : "unchanged", "chars", updated.length(), "replacements", replacements);
        }
    }

    private void requireUnchanged(SkillReviewState state, Path path, String current) throws Exception {
        SkillReviewState.ReadVersion read = state.version(path);
        if (read == null) throw new IllegalArgumentException("修改已有文件前必须使用 workspace_read 读取");
        if (!read.hash().equals(readVersion(path, current).hash())) throw new IllegalArgumentException("文件自读取后已改变，请重新读取后编辑");
    }

    private String required(Map<String, Object> args, String key) {
        if (!args.containsKey(key) || args.get(key) == null) throw new IllegalArgumentException(key + " is required");
        return String.valueOf(args.get(key));
    }

    /** 写入事实与可注册状态分别记录；失败或超时也刷新已成功写入的文件。 */
    public RefreshResult refresh(SkillReviewState state) {
        if (state.changes().isEmpty()) return new RefreshResult("NOT_NEEDED", "无文件变更");
        try {
            registry.refresh();
            Set<String> invalid = new LinkedHashSet<>();
            for (var entry : state.changedPaths().entrySet()) {
                SkillReviewState.ChangedFile changed = entry.getValue();
                Optional<SkillDefinition> registered = registry.findSkill(changed.skillName());
                // 同名包可能来自另一个配置根；必须核对实际修改的包，不能只查名字存在。
                if (registered.isEmpty() || !registered.get().getBasePath().resolve(changed.path()).toRealPath()
                        .equals(entry.getKey().toRealPath())) invalid.add(changed.skillName());
            }
            return invalid.isEmpty() ? new RefreshResult("SUCCESS", "变更包均已注册")
                    : new RefreshResult("INVALID", "文件已写入但包未注册：" + String.join(",", invalid));
        } catch (Exception e) { return new RefreshResult("FAILED", "文件已写入但注册表刷新失败"); }
    }
    public record RefreshResult(String status, String summary) { }
}
