package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * master 风格 skill 发现与说明书读取工具。
 *
 * <p>空参只返回摘要；传入 `skill_name` 后才返回单个 skill 的完整说明，避免污染初始上下文。</p>
 */
/** @deprecated 使用 skills_search 和 skill_view。 */
@RequiredArgsConstructor
@Deprecated
public class SkillTool implements BaseTool {

    private final String name;
    private final String description;
    private final Object params;
    private final SkillRegistry skillRegistry;
    private final SkillRuntimeOptions skillRuntimeOptions;

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Object params() {
        return params;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        Map<String, Object> arguments = input instanceof ToolInvokeRequest request && request.getArguments() != null
                ? request.getArguments()
                : Map.of();
        String skillName = stringValue(arguments.get("skill_name"));
        if (!StringUtils.hasText(skillName)) {
            Map<String, Object> result = buildListPayload();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skills = (List<Map<String, Object>>) result.get("skills");
            boolean truncated = Boolean.TRUE.equals(result.get("truncated"));
            return ToolResultPayload.success(
                    "Returned available skill list.",
                    SkillObservationFormatter.skillList(skills, ((Number) result.get("totalCount")).intValue(), truncated),
                    result
            );
        }
        SkillDefinition skill;
        try {
            skill = skillRegistry.getRequiredSkill(skillName);
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("skillName", skillName);
            result.put("recoveryHint", "Call skill_tool without skill_name to list available skills.");
            return ToolResultPayload.builder()
                    .success(false)
                    .status("failed")
                    .errorCode("skill_not_found")
                    .errorMessage("Skill not found: " + skillName)
                    .toolResult("Skill not found: " + skillName)
                    .llmObservation("未找到 skill " + skillName + "。请先空参调用 skill_tool 查看可用 skill。")
                    .structuredOutput(result)
                    .build();
        }
        Map<String, Object> result = buildDetailPayload(
                skill,
                asBoolean(arguments.get("include_content"), true),
                asBoolean(arguments.get("include_scripts"), true),
                asBoolean(arguments.get("include_references"), false)
        );
        return ToolResultPayload.success(
                "Read skill " + skill.getName() + ".",
                SkillObservationFormatter.skillDetail(skill, result),
                result
        );
    }

    private Map<String, Object> buildListPayload() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<SkillDefinition> allSkills = skillRegistry.listSkills();
        List<Map<String, Object>> skills = allSkills.stream()
                .limit(skillRuntimeOptions.getMaxListEntries())
                .map(skill -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("skillId", skill.getSkillId());
                    item.put("name", skill.getName());
                    item.put("title", skill.getTitle());
                    item.put("description", skill.getDescription());
                    item.put("scenario", skill.getScenario());
                    item.put("version", skill.getVersion());
                    item.put("sourceId", skill.getSourceId());
                    item.put("tags", skill.getTags());
                    item.put("constraints", skill.getConstraints());
                    item.put("scriptCount", skill.getScripts() == null ? 0 : skill.getScripts().size());
                    item.put("scripts", scriptSummaries(skill));
                    item.put("compatibilityStatus", skill.getCompatibilityStatus());
                    return item;
                })
                .toList();
        result.put("skills", skills);
        result.put("count", skills.size());
        result.put("totalCount", allSkills.size());
        result.put("truncated", allSkills.size() > skills.size());
        return result;
    }

    private Map<String, Object> buildDetailPayload(SkillDefinition skill,
                                                   boolean includeContent,
                                                   boolean includeScripts,
                                                   boolean includeReferences) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skillId", skill.getSkillId());
        result.put("name", skill.getName());
        result.put("title", skill.getTitle());
        result.put("description", skill.getDescription());
        result.put("scenario", skill.getScenario());
        result.put("version", skill.getVersion());
        result.put("sourceId", skill.getSourceId());
        result.put("owner", skill.getOwner());
        result.put("instructionSummary", skill.getInstructionSummary());
        result.put("contentRef", skill.getContentRef());
        result.put("tags", skill.getTags());
        result.put("constraints", skill.getConstraints());
        result.put("compatibilityStatus", skill.getCompatibilityStatus());
        result.put("basePath", skill.getBasePath() == null ? null : skill.getBasePath().toString());
        result.put("scripts", includeScripts ? scriptSummaries(skill) : List.of());
        result.put("references", includeReferences ? skill.getReferences() : List.of());
        result.put("assets", includeReferences ? skill.getAssets() : List.of());
        result.put("content", includeContent && skillRuntimeOptions.isAllowFullContentRead() ? readContent(skill) : null);
        return result;
    }

    private String readContent(SkillDefinition skill) {
        if (skill.getBasePath() == null) return skill.getContent();
        try {
            String text = Files.readString(skill.getBasePath().resolve("SKILL.md"), StandardCharsets.UTF_8);
            int limit = skillRuntimeOptions.getMaxReadChars();
            return limit > 0 && text.length() > limit ? text.substring(0, limit) : text;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Skill document unavailable: " + skill.getName(), failure);
        }
    }

    private List<Map<String, Object>> scriptSummaries(SkillDefinition skill) {
        if (skill.getScripts() == null || skill.getScripts().isEmpty()) {
            return List.of();
        }
        return skill.getScripts().stream()
                .map(script -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("scriptName", script.getScriptName());
                    item.put("runtime", script.getRuntime());
                    item.put("description", script.getDescription());
                    item.put("relativePath", script.getRelativePath());
                    item.put("timeoutSeconds", script.getTimeoutSeconds());
                    item.put("declarationSource", script.getDeclarationSource());
                    return item;
                })
                .toList();
    }

    private boolean asBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
