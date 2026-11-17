package com.flocier.domain.agent.runtime.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `SKILL.md` 读取和 front matter 解析器。
 *
 * <p>master 风格 skill 以 Markdown 说明书为中心；front matter 只提取选择阶段需要的轻量元数据。</p>
 */
@Component
public class SkillMarkdownParser {

    public String parse(Path markdownPath, int maxReadChars) throws IOException {
        String content = Files.readString(markdownPath, StandardCharsets.UTF_8);
        if (maxReadChars <= 0 || content.length() <= maxReadChars) {
            return content;
        }
        return content.substring(0, maxReadChars);
    }

    public ParsedSkillMarkdown parseWithFrontMatter(Path markdownPath, int maxReadChars) throws IOException {
        String original = parse(markdownPath, maxReadChars);
        Map<String, String> frontMatter = new LinkedHashMap<>();
        String body = original;
        if (original.startsWith("---")) {
            int end = original.indexOf("\n---", 3);
            if (end > 0) {
                String rawFrontMatter = original.substring(3, end).trim();
                body = original.substring(end + 4).trim();
                for (String line : rawFrontMatter.split("\\R")) {
                    int index = line.indexOf(':');
                    if (index <= 0) {
                        continue;
                    }
                    String key = line.substring(0, index).trim();
                    String value = unquote(line.substring(index + 1).trim());
                    frontMatter.put(key, value);
                }
            }
        }
        return new ParsedSkillMarkdown(frontMatter, body, original);
    }

    public String summarize(String content, String fallback, int maxChars) {
        if (!StringUtils.hasText(content)) {
            return fallback == null ? "" : fallback;
        }
        String normalized = content.replace("#", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (!StringUtils.hasText(normalized)) {
            return fallback == null ? "" : fallback;
        }
        int limit = maxChars <= 0 ? 300 : maxChars;
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedSkillMarkdown {
        private Map<String, String> frontMatter = new LinkedHashMap<>();
        private String body = "";
        private String rawContent = "";
    }
}
