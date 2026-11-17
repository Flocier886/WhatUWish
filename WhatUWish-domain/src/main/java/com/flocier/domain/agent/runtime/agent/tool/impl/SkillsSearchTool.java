package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.skill.SkillCatalogSearch;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPromptIndexBuilder;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只检索 skill 元数据；正文由 skill_view 按需读取。 */
@RequiredArgsConstructor
public class SkillsSearchTool implements BaseTool {

    private final SkillRegistry registry;

    @Override
    public String name() {
        return "skills_search";
    }

    @Override
    public String description() {
        return "按关键词搜索可用 skill，只返回名称、短描述和分类。找到候选后用 skill_view 加载完整 SKILL.md。";
    }

    @Override
    public Object params() {
        return Map.of("type", "object", "properties", Map.of(
                "query", Map.of("type", "string", "description", "搜索关键词，例如 SQL 销售趋势分析"),
                "category", Map.of("type", "string", "description", "可选分类"),
                "limit", Map.of("type", "integer", "description", "返回条数，默认 10，最大 25")
        ), "required", List.of("query"));
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        Map<String, Object> args = input instanceof ToolInvokeRequest request && request.getArguments() != null
                ? request.getArguments() : Map.of();
        String query = string(args.getOrDefault("query", args.get("text")));
        String category = string(args.get("category"));
        int limit = integer(args.get("limit"));
        List<SkillCatalogSearch.Hit> hits = SkillCatalogSearch.search(registry.listSkills(), query, category, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder observation = new StringBuilder("匹配的 skill（").append(hits.size())
                .append(" 项；调用 skill_view(name) 读取完整说明）：\n");
        for (SkillCatalogSearch.Hit hit : hits) {
            SkillDefinition skill = hit.skill();
            String description = SkillPromptIndexBuilder.truncate(
                    skill.getDescription(), SkillPromptIndexBuilder.SEARCH_DESCRIPTION_CHARS);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", skill.getSkillId());
            row.put("name", skill.getName());
            row.put("description", description);
            row.put("category", skill.getCategory());
            row.put("tags", skill.getTags() == null ? List.of() : skill.getTags());
            row.put("score", hit.score());
            items.add(row);
            observation.append("- ").append(skill.getName()).append(" [")
                    .append(skill.getCategory()).append("]: ").append(description).append('\n');
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("items", items);
        output.put("total", items.size());
        output.put("hint", "Use skill_view(name) to load full SKILL.md");
        return ToolResultPayload.success("Found " + items.size() + " skills.", observation.toString(), output);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 10 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 10;
        }
    }
}
