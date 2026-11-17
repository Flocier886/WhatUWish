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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取单个 skill 包内文件的工具。
 *
 * <p>用于让 agent 按需查看 references、脚本说明或示例文件，默认带行号并受最大读取长度限制。</p>
 */
/** @deprecated 使用 skill_view(file_path) 或 workspace_read(skills/...)。 */
@Deprecated
public class SkillReadTool extends AbstractSkillPathTool {

    public SkillReadTool(String name, String description, Object params, SkillRegistry skillRegistry,
                         SkillRuntimeOptions options, SkillPathGuard pathGuard) {
        super(name, description, params, skillRegistry, options, pathGuard);
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> args = arguments(input);
            SkillDefinition skill = requireSkill(args, context);
            Path path = resolveSkillPath(skill, stringValue(args.get("path")));
            if (!Files.isRegularFile(path)) {
                return failed("skill_file_not_found", "Skill file not found: " + relative(skill, path));
            }
            int startLine = Math.max(1, intValue(args.get("start_line"), 1));
            int lineCount = Math.max(1, intValue(args.get("line_count"), 120));
            List<String> lines = com.flocier.domain.agent.runtime.agent.AgentRunMode.isReview(context)
                    ? com.flocier.domain.agent.runtime.memory.review.SkillReviewFiles.read(context, path).lines().toList()
                    : Files.readAllLines(path, StandardCharsets.UTF_8);
            int from = Math.min(startLine - 1, lines.size());
            int to = (int) Math.min((long) from + lineCount, lines.size());
            StringBuilder content = new StringBuilder();
            int shownLines = 0;
            boolean clippedByChars = false;
            for (int index = from; index < to; index++) {
                String line = (index + 1) + " | " + lines.get(index) + '\n';
                int remaining = options.getMaxReadChars() <= 0
                        ? line.length() : options.getMaxReadChars() - content.length();
                if (remaining <= 0) {
                    clippedByChars = true;
                    break;
                }
                content.append(line, 0, Math.min(line.length(), remaining));
                shownLines++;
                if (remaining < line.length()) {
                    clippedByChars = true;
                    break;
                }
            }
            boolean truncated = to < lines.size() || clippedByChars || from + shownLines < lines.size();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("skillName", skill.getName());
            output.put("path", relative(skill, path));
            output.put("startLine", startLine);
            output.put("lineCount", shownLines);
            output.put("totalLines", lines.size());
            output.put("content", content.toString());
            output.put("truncated", truncated);
            String relativePath = relative(skill, path);
            return ToolResultPayload.success(
                    "Read skill file " + relativePath,
                    SkillObservationFormatter.readFile(
                            skill.getName(),
                            relativePath,
                            startLine,
                            shownLines,
                            lines.size(),
                            truncated,
                            content.toString()
                    ),
                    output
            );
        } catch (Exception e) {
            return failed("skill_read_failed", e.getMessage());
        }
    }
}
