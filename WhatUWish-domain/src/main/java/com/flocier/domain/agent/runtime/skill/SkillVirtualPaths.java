package com.flocier.domain.agent.runtime.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 对齐参考项目的 skills/ 虚拟路径：工作区工具直接访问运行时 skill 库。 */
public final class SkillVirtualPaths {
    private final SkillRuntimeOptions options;
    private final SkillPathGuard guard;

    public SkillVirtualPaths(SkillRuntimeOptions options, SkillPathGuard guard) {
        this.options = options;
        this.guard = guard;
    }

    public Optional<Path> resolve(String raw) {
        if (options == null || !options.isEnabled() || raw == null || raw.isBlank()) return Optional.empty();
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        if (!value.equals("skills") && !value.startsWith("skills/")) return Optional.empty();
        Path root = root();
        String relative = value.equals("skills") ? "" : value.substring("skills/".length());
        if (relative.contains(":") || relative.startsWith("/") || relative.startsWith("~"))
            throw new IllegalArgumentException("非法 skill 虚拟路径");
        for (String part : relative.split("/")) {
            if (part.equals("..")) throw new IllegalArgumentException("skill 路径超出库根");
        }
        return Optional.of(guard.assertPathUnderRoot(root.resolve(relative), root));
    }

    public Path root() {
        if (options == null || !options.isEnabled() || options.getDirectories() == null
                || options.getDirectories().isEmpty()) throw new IllegalArgumentException("skill 库未配置");
        Path root = SkillPathGuard.resolveConfiguredRoot(options.getDirectories().get(0));
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("skill 库不存在");
        return root;
    }

    public boolean contains(Path path) {
        if (options == null || !options.isEnabled()) return false;
        try { return path.toAbsolutePath().normalize().startsWith(root()); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    public String visible(Path path) {
        Path root = root();
        Path safe = guard.assertPathUnderRoot(path, root);
        String relative = root.relativize(safe).toString().replace('\\', '/');
        return relative.isBlank() ? "skills" : "skills/" + relative;
    }
}
