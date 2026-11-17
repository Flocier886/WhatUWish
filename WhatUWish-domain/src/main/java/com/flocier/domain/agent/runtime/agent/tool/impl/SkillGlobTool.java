package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 skill 包内按 glob 模式查找文件的工具。
 *
 * <p>只返回匹配文件路径，不读取内容，适合先定位资料文件或脚本辅助文件。</p>
 */
/** @deprecated 使用 workspace_glob(skills/...)。 */
@Deprecated
public class SkillGlobTool extends AbstractSkillPathTool {

    public SkillGlobTool(String name, String description, Object params, SkillRegistry skillRegistry,
                         SkillRuntimeOptions options, SkillPathGuard pathGuard) {
        super(name, description, params, skillRegistry, options, pathGuard);
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> args = arguments(input);
            SkillDefinition skill = requireSkill(args, context);
            Path path = resolveSkillPath(skill, stringValue(args.get("path")));
            String pattern = stringValue(args.get("pattern"));
            if (pattern == null || pattern.isBlank()) {
                pattern = "**";
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            int max = Math.max(1, options.getMaxSearchMatches());
            try (var stream = Files.walk(path)) {
                List<String> matches = stream
                        .filter(Files::isRegularFile).filter(item -> {
                            try { pathGuard.assertPathUnderRoot(item, skill.getBasePath()); return true; }
                            catch (IllegalArgumentException denied) { return false; }
                        })
                        .map(item -> skill.getBasePath().relativize(item.toAbsolutePath().normalize()))
                        .filter(matcher::matches)
                        .map(item -> item.toString().replace('\\', '/'))
                        .limit(max + 1L)
                        .toList();
                boolean truncated = matches.size() > max;
                List<String> visibleMatches = truncated ? matches.subList(0, max) : matches;
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("skillName", skill.getName());
                output.put("path", relative(skill, path));
                output.put("pattern", pattern);
                output.put("matches", visibleMatches);
                output.put("truncated", truncated);
                return ToolResultPayload.success(
                        "Matched skill files",
                        SkillObservationFormatter.glob(skill.getName(), relative(skill, path), pattern, visibleMatches, truncated),
                        output
                );
            }
        } catch (Exception e) {
            return failed("skill_glob_failed", e.getMessage());
        }
    }
}
