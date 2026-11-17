package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出 skill 包内目录结构的工具。
 *
 * <p>用于让 agent 先了解 skill 包有哪些 references、assets、scripts，再决定下一步读取或搜索。</p>
 */
/** @deprecated 使用 workspace_list(skills/...)。 */
@Deprecated
public class SkillListDirectoryTool extends AbstractSkillPathTool {

    public SkillListDirectoryTool(String name, String description, Object params, SkillRegistry skillRegistry,
                                  SkillRuntimeOptions options, SkillPathGuard pathGuard) {
        super(name, description, params, skillRegistry, options, pathGuard);
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> args = arguments(input);
            SkillDefinition skill = requireSkill(args, context);
            Path path = resolveSkillPath(skill, stringValue(args.get("path")));
            if (!Files.isDirectory(path)) {
                return failed("skill_directory_not_found", "Skill directory not found: " + relative(skill, path));
            }
            int maxDepth = Math.max(1, intValue(args.get("max_depth"), 2));
            int maxEntries = Math.max(1, options.getMaxListEntries());
            try (var stream = Files.walk(path, maxDepth)) {
                List<Map<String, Object>> entries = stream
                        .filter(item -> !item.equals(path))
                        .sorted(Comparator.comparing(item -> relative(skill, item)))
                        .limit(maxEntries + 1L)
                        .map(item -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("path", relative(skill, item));
                            entry.put("type", Files.isDirectory(item) ? "directory" : "file");
                            try {
                                entry.put("size", Files.isRegularFile(item) ? Files.size(item) : null);
                            } catch (Exception ignored) {
                                entry.put("size", null);
                            }
                            return entry;
                        })
                        .toList();
                boolean truncated = entries.size() > maxEntries;
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("skillName", skill.getName());
                output.put("path", relative(skill, path));
                output.put("entries", truncated ? entries.subList(0, maxEntries) : entries);
                output.put("truncated", truncated);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> visibleEntries = (List<Map<String, Object>>) output.get("entries");
                String relativePath = relative(skill, path);
                return ToolResultPayload.success(
                        "Listed skill directory " + relativePath,
                        SkillObservationFormatter.directory(skill.getName(), relativePath, visibleEntries, truncated),
                        output
                );
            }
        } catch (Exception e) {
            return failed("skill_list_directory_failed", e.getMessage());
        }
    }
}
