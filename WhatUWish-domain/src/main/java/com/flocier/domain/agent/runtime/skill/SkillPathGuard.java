package com.flocier.domain.agent.runtime.skill;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;

/**
 * Skill 路径边界守卫。
 * 统一限制 skill 只能访问注册目录下的文件。
 */
@Component
public class SkillPathGuard {

    public Path assertPathAllowed(Path path, List<Path> allowedRoots) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (normalized.startsWith(normalizedRoot)) {
                return assertPathUnderRoot(normalized, normalizedRoot);
            }
        }
        throw new IllegalArgumentException("Skill path is outside allowed roots: " + normalized);
    }

    /**
     * 校验候选路径必须位于指定 skill 包根目录内。
     * 这是 master 风格 skill 工具最重要的安全边界，避免模型通过相对路径读取包外文件。
     */
    public Path assertPathUnderRoot(Path path, Path root) {
        Path normalized = path.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Skill path is outside skill package: " + normalized);
        }
        // realPath 同时识别符号链接与 Windows junction；不存在的尾路径由最近祖先推导。
        if (!realLocation(normalized).startsWith(realLocation(normalizedRoot))) {
            throw new IllegalArgumentException("Skill 路径通过链接越过包边界");
        }
        return normalized;
    }

    private Path realLocation(Path path) {
        try {
            if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return path.toRealPath();
            Path parent = path.getParent();
            if (parent == null) return path;
            return realLocation(parent).resolve(path.getFileName()).normalize();
        } catch (IOException e) { throw new IllegalArgumentException("Skill 路径无法安全解析", e); }
    }

    /** 与注册中心一致：模块启动时也可定位上一级已有的相对 Skill 根目录。 */
    public static Path resolveConfiguredRoot(String directory) {
        Path candidate = Path.of(directory).toAbsolutePath().normalize();
        if (Files.isDirectory(candidate) || Path.of(directory).isAbsolute()) return candidate;
        Path parent = Path.of("").toAbsolutePath().getParent();
        if (parent != null && Files.isDirectory(parent.resolve(directory))) return parent.resolve(directory).normalize();
        return candidate;
    }
}
