package com.flocier.domain.agent.runtime.skill;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 社区风格 skill 脚本发现器。
 *
 * <p>本类只负责把 `scripts/` 和 `scripts.yaml` 归一成注册脚本清单；
 * 真正执行脚本时仍必须由 registry 通过 `skill_name + script_name` 解析，
 * 不能接受模型直接传入任意脚本路径。</p>
 */
@Component
public class SkillScriptDiscoverer {

    private static final Map<String, String> RUNTIME_BY_EXTENSION = Map.of(
            ".py", "python",
            ".js", "node",
            ".mjs", "node",
            ".cjs", "node",
            ".sh", "shell",
            ".ps1", "powershell",
            ".bat", "bat",
            ".cmd", "bat"
    );

    public Map<String, SkillScriptDefinition> discover(Path skillDirectory,
                                                       SkillRuntimeOptions options,
                                                       SkillPathGuard pathGuard) throws IOException {
        Map<String, SkillScriptDefinition> scripts = new LinkedHashMap<>();
        discoverScriptsDirectory(skillDirectory, options, pathGuard, scripts);
        discoverScriptsYaml(skillDirectory, options, pathGuard, scripts);
        return scripts;
    }

    private void discoverScriptsDirectory(Path skillDirectory,
                                          SkillRuntimeOptions options,
                                          SkillPathGuard pathGuard,
                                          Map<String, SkillScriptDefinition> scripts) throws IOException {
        Path scriptsDir = skillDirectory.resolve("scripts");
        if (!Files.isDirectory(scriptsDir)) {
            return;
        }
        try (var stream = Files.walk(scriptsDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> skillDirectory.relativize(path).toString()))
                    .toList();
            for (Path file : files) {
                String runtime = runtimeFor(file);
                if (!StringUtils.hasText(runtime) || !isRuntimeAllowed(runtime, options)) {
                    continue;
                }
                Path absolutePath = pathGuard.assertPathUnderRoot(file, skillDirectory);
                String relativePath = normalizeRelative(skillDirectory.relativize(absolutePath));
                String scriptName = stripExtension(file.getFileName().toString());
                scripts.putIfAbsent(scriptName, SkillScriptDefinition.builder()
                        .scriptName(scriptName)
                        .relativePath(relativePath)
                        .absolutePath(absolutePath)
                        .runtime(runtime)
                        .description("Auto-discovered script " + scriptName + ".")
                        .timeoutSeconds(options.getDefaultScriptTimeoutSeconds())
                        .declarationSource("scripts_dir")
                        .build());
            }
        }
    }

    private void discoverScriptsYaml(Path skillDirectory,
                                     SkillRuntimeOptions options,
                                     SkillPathGuard pathGuard,
                                     Map<String, SkillScriptDefinition> scripts) throws IOException {
        Path yaml = skillDirectory.resolve("scripts.yaml");
        if (!Files.isRegularFile(yaml)) {
            return;
        }
        Map<String, Map<String, String>> declared = parseScriptsYaml(yaml);
        for (Map.Entry<String, Map<String, String>> entry : declared.entrySet()) {
            String scriptName = entry.getKey();
            Map<String, String> raw = entry.getValue();
            String pathValue = firstNonBlank(raw.get("path"), raw.get("script"), raw.get("relative_path"));
            if (!StringUtils.hasText(scriptName) || !StringUtils.hasText(pathValue)) {
                continue;
            }
            Path absolutePath = pathGuard.assertPathUnderRoot(skillDirectory.resolve(pathValue), skillDirectory);
            String runtime = firstNonBlank(raw.get("runtime"), runtimeFor(absolutePath));
            if (!isRuntimeAllowed(runtime, options)) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (!Set.of("name", "script_name", "path", "script", "relative_path", "runtime", "description", "desc").contains(key)) {
                    metadata.put(key, value);
                }
            });
            scripts.put(scriptName, SkillScriptDefinition.builder()
                    .scriptName(scriptName)
                    .relativePath(normalizeRelative(skillDirectory.relativize(absolutePath)))
                    .absolutePath(absolutePath)
                    .runtime(runtime)
                    .description(firstNonBlank(raw.get("description"), raw.get("desc"), "Declared script " + scriptName + "."))
                    .timeoutSeconds(options.getDefaultScriptTimeoutSeconds())
                    .declarationSource("scripts_yaml")
                    .metadata(metadata)
                    .build());
        }
    }

    private Map<String, Map<String, String>> parseScriptsYaml(Path yaml) throws IOException {
        List<String> lines = Files.readAllLines(yaml, StandardCharsets.UTF_8);
        Map<String, Map<String, String>> scripts = new LinkedHashMap<>();
        String currentName = null;
        Map<String, String> currentListItem = null;

        for (String rawLine : lines) {
            String line = stripComment(rawLine);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            int indent = countIndent(line);
            String trimmed = line.trim();
            if (indent == 0 && "scripts:".equals(trimmed)) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                currentListItem = new LinkedHashMap<>();
                currentName = null;
                String rest = trimmed.substring(2).trim();
                if (rest.contains(":")) {
                    putScalar(currentListItem, rest);
                    currentName = firstNonBlank(currentListItem.get("name"), currentListItem.get("script_name"));
                    if (StringUtils.hasText(currentName)) {
                        scripts.put(currentName, currentListItem);
                    }
                }
                continue;
            }
            if (indent == 2 && trimmed.endsWith(":")) {
                currentName = trimmed.substring(0, trimmed.length() - 1).trim();
                currentListItem = null;
                scripts.computeIfAbsent(currentName, key -> new LinkedHashMap<>());
                continue;
            }
            if (indent >= 2 && trimmed.contains(":")) {
                if (currentListItem != null) {
                    putScalar(currentListItem, trimmed);
                    currentName = firstNonBlank(currentListItem.get("name"), currentListItem.get("script_name"), currentName);
                    if (StringUtils.hasText(currentName)) {
                        scripts.put(currentName, currentListItem);
                    }
                } else if (StringUtils.hasText(currentName)) {
                    putScalar(scripts.computeIfAbsent(currentName, key -> new LinkedHashMap<>()), trimmed);
                }
            }
        }
        return scripts;
    }

    private void putScalar(Map<String, String> target, String text) {
        int index = text.indexOf(':');
        if (index < 0) {
            return;
        }
        String key = text.substring(0, index).trim();
        String value = unquote(text.substring(index + 1).trim());
        target.put(key, value);
    }

    private boolean isRuntimeAllowed(String runtime, SkillRuntimeOptions options) {
        if (!StringUtils.hasText(runtime)) {
            return false;
        }
        List<String> allowed = options.getAllowedScriptRuntimes();
        return allowed == null || allowed.isEmpty() || allowed.contains(runtime);
    }

    private String runtimeFor(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        for (Map.Entry<String, String> entry : RUNTIME_BY_EXTENSION.entrySet()) {
            if (fileName.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index <= 0 ? fileName : fileName.substring(0, index);
    }

    private String normalizeRelative(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String stripComment(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        return trimmed.startsWith("#") ? "" : line;
    }

    private int countIndent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private String unquote(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
