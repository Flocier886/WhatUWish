package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 在 skill 包内搜索文本内容的工具。
 *
 * <p>用于检索 SKILL.md、references 或脚本资料中的关键词，返回路径、行号和命中文本。</p>
 */
/** @deprecated 使用 workspace_grep(skills/...)。 */
@Deprecated
public class SkillGrepTool extends AbstractSkillPathTool {

    public SkillGrepTool(String name, String description, Object params, SkillRegistry skillRegistry,
                         SkillRuntimeOptions options, SkillPathGuard pathGuard) {
        super(name, description, params, skillRegistry, options, pathGuard);
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> args = arguments(input);
            SkillDefinition skill = requireSkill(args, context);
            Path path = resolveSkillPath(skill, stringValue(args.get("path")));
            String rawPattern = stringValue(args.get("pattern"));
            if (rawPattern == null || rawPattern.isBlank()) {
                return failed("skill_grep_pattern_missing", "pattern is required");
            }
            boolean regex = boolValue(args.get("regex"), false);
            boolean caseSensitive = boolValue(args.get("case_sensitive"), false);
            Pattern pattern = regex
                    ? Pattern.compile(rawPattern, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE)
                    : Pattern.compile(Pattern.quote(rawPattern), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            List<Path> files;
            if (Files.isRegularFile(path)) {
                files = List.of(path);
            } else if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    files = stream.filter(Files::isRegularFile).filter(item -> {
                            try { pathGuard.assertPathUnderRoot(item, skill.getBasePath()); return true; }
                            catch (IllegalArgumentException denied) { return false; }
                        }).toList();
                }
            } else {
                return failed("skill_grep_path_not_found", "Skill path not found: " + relative(skill, path));
            }
            int max = Math.max(1, options.getMaxSearchMatches());
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Path file : files) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    continue;
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("path", relative(skill, file));
                        item.put("line", i + 1);
                        item.put("text", lines.get(i));
                        matches.add(item);
                        if (matches.size() > max) {
                            break;
                        }
                    }
                }
                if (matches.size() > max) {
                    break;
                }
            }
            boolean truncated = matches.size() > max;
            List<Map<String, Object>> visibleMatches = truncated ? matches.subList(0, max) : matches;
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("skillName", skill.getName());
            output.put("path", relative(skill, path));
            output.put("pattern", rawPattern);
            output.put("matches", visibleMatches);
            output.put("truncated", truncated);
            return ToolResultPayload.success(
                    "Searched skill files",
                    SkillObservationFormatter.grep(skill.getName(), relative(skill, path), rawPattern, visibleMatches, truncated),
                    output
            );
        } catch (Exception e) {
            return failed("skill_grep_failed", e.getMessage());
        }
    }
}
