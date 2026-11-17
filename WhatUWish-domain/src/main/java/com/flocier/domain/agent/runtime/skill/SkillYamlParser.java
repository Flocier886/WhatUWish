package com.flocier.domain.agent.runtime.skill;

import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量版 skill.yaml 解析器。
 * 第一版只解析当前项目需要的常用字段，后续可以替换为更完整的 YAML 方案。
 */
@Component
public class SkillYamlParser {

    public ParsedSkillYaml parse(Path yamlPath) throws IOException {
        List<String> lines = Files.readAllLines(yamlPath, StandardCharsets.UTF_8);
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        Map<String, String> promptPaths = new LinkedHashMap<>();

        String currentListKey = null;
        String currentMapKey = null;
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String noComment = stripComment(rawLine);
            if (!StringUtils.hasText(noComment)) {
                continue;
            }
            int indent = countIndent(noComment);
            String trimmed = noComment.trim();

            if (indent == 0 && trimmed.endsWith(":")) {
                currentListKey = trimmed.substring(0, trimmed.length() - 1).trim();
                if ("promptTemplates".equals(currentListKey)) {
                    currentMapKey = currentListKey;
                } else if ("actions".equals(currentListKey)) {
                    // master-style 主路径不再解析旧 action 块，只兼容地跳过。
                    currentMapKey = "ignored";
                    currentListKey = null;
                } else {
                    lists.computeIfAbsent(currentListKey, key -> new ArrayList<>());
                    currentMapKey = null;
                }
                continue;
            }

            if ("ignored".equals(currentMapKey) && indent > 0) {
                continue;
            }

            if (indent == 2 && "promptTemplates".equals(currentMapKey) && trimmed.contains(":")) {
                int index = trimmed.indexOf(':');
                String key = trimmed.substring(0, index).trim();
                String value = unquote(trimmed.substring(index + 1).trim());
                promptPaths.put(key, value);
                continue;
            }

            if (trimmed.startsWith("- ")) {
                if (currentListKey != null) {
                    lists.computeIfAbsent(currentListKey, key -> new ArrayList<>())
                            .add(unquote(trimmed.substring(2).trim()));
                }
                continue;
            }

            if (trimmed.contains(":")) {
                int index = trimmed.indexOf(':');
                String key = trimmed.substring(0, index).trim();
                String value = trimmed.substring(index + 1).trim();
                if (value.startsWith("{") || value.startsWith("[")) {
                    scalars.put(key, value);
                } else {
                    scalars.put(key, unquote(value));
                }
                currentListKey = null;
                currentMapKey = null;
            }
        }

        return ParsedSkillYaml.builder()
                .scalars(scalars)
                .lists(lists)
                .promptTemplatePaths(promptPaths)
                .build();
    }

    private String stripComment(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("#")) {
            return "";
        }
        return line;
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedSkillYaml {
        @Builder.Default
        private Map<String, String> scalars = new LinkedHashMap<>();
        @Builder.Default
        private Map<String, List<String>> lists = new LinkedHashMap<>();
        @Builder.Default
        private Map<String, String> promptTemplatePaths = new LinkedHashMap<>();

        public Map<String, Object> scalarJsonMap(String key) {
            String raw = scalars.get(key);
            if (!StringUtils.hasText(raw)) {
                return new LinkedHashMap<>();
            }
            try {
                return JSON.parseObject(raw);
            } catch (Exception e) {
                return new LinkedHashMap<>();
            }
        }
    }
}
