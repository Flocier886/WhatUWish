package com.flocier.domain.agent.runtime.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 参考项目的三级预算索引：完整描述、短描述、分类摘要。 */
public final class SkillPromptIndexBuilder {

    public static final int DEFAULT_MAX_CHARS = 6000;
    public static final int MAX_DESCRIPTION_CHARS = 80;
    public static final int SEARCH_DESCRIPTION_CHARS = 300;
    private static final int COMPACT_DESCRIPTION_CHARS = 40;
    private static final int MAX_INDEX_SKILLS = 200;
    public static final String OPEN = "<available_skills>";

    private SkillPromptIndexBuilder() {
    }

    public static String build(List<SkillDefinition> skills) {
        return build(skills, DEFAULT_MAX_CHARS);
    }

    public static String build(List<SkillDefinition> skills, int maxChars) {
        List<SkillDefinition> sorted = new ArrayList<>(skills == null ? List.of() : skills);
        sorted.removeIf(skill -> skill == null || !skill.isEnabled()
                || skill.getName() == null || skill.getName().isBlank());
        sorted.sort(Comparator.comparing(SkillDefinition::getName, String.CASE_INSENSITIVE_ORDER));
        boolean capped = sorted.size() > MAX_INDEX_SKILLS;
        Map<String, List<SkillDefinition>> categories = new TreeMap<>();
        for (SkillDefinition skill : sorted.subList(0, Math.min(sorted.size(), MAX_INDEX_SKILLS))) {
            String category = skill.getCategory() == null || skill.getCategory().isBlank()
                    ? "general" : skill.getCategory();
            categories.computeIfAbsent(category, ignored -> new ArrayList<>()).add(skill);
        }
        String full = render(categories, MAX_DESCRIPTION_CHARS, capped);
        if (maxChars <= 0 || full.length() <= maxChars) return full;
        String compact = render(categories, COMPACT_DESCRIPTION_CHARS, true);
        if (compact.length() <= maxChars) return compact;
        return renderWithBudget(categories, maxChars);
    }

    public static String truncate(String text, int maxChars) {
        String value = text == null ? "" : text.strip();
        if (maxChars <= 0 || value.length() <= maxChars) return value;
        return maxChars == 1 ? "…" : value.substring(0, maxChars - 1) + "…";
    }

    private static StringBuilder header(boolean degraded) {
        StringBuilder text = new StringBuilder("# Skills\n")
                .append("如果当前任务与某个 skill 相关，必须先调用 skill_view 加载它。\n")
                .append("如果无法判断 skill 名称，先调用 skills_search。\n")
                .append("不要直接使用 workspace_read 搜索 skill 主文档；需要附属文件或分段续读时使用 workspace_* 的 skills/ 路径。\n\n");
        if (degraded) text.append("（索引已按预算精简，可用 skills_search 检索完整列表）\n");
        return text.append(OPEN).append('\n');
    }

    private static String render(Map<String, List<SkillDefinition>> categories, int descriptionChars,
                                 boolean degraded) {
        StringBuilder text = header(degraded);
        if (categories.isEmpty()) text.append("  （当前没有可用 skill）\n");
        for (var entry : categories.entrySet()) {
            text.append("  ").append(entry.getKey()).append(":\n");
            for (SkillDefinition skill : dedupe(entry.getValue()).values()) {
                text.append("    - ").append(skill.getName()).append(": ")
                        .append(truncate(skill.getDescription(), descriptionChars)).append('\n');
            }
        }
        return text.append("</available_skills>\n").toString();
    }

    private static String renderWithBudget(Map<String, List<SkillDefinition>> categories, int maxChars) {
        StringBuilder text = header(true);
        int closing = "</available_skills>\n".length();
        for (var entry : categories.entrySet()) {
            Map<String, SkillDefinition> unique = dedupe(entry.getValue());
            StringBuilder block = new StringBuilder("  ").append(entry.getKey()).append(":\n");
            for (SkillDefinition skill : unique.values()) {
                block.append("    - ").append(skill.getName()).append(": ")
                        .append(truncate(skill.getDescription(), COMPACT_DESCRIPTION_CHARS)).append('\n');
            }
            String compact = "  " + entry.getKey() + ": " + unique.size() + " 个 skill（用 skills_search 查询）\n";
            if (text.length() + block.length() + closing <= maxChars) text.append(block);
            else if (text.length() + compact.length() + closing <= maxChars) text.append(compact);
        }
        return text.append("</available_skills>\n").toString();
    }

    private static Map<String, SkillDefinition> dedupe(List<SkillDefinition> skills) {
        Map<String, SkillDefinition> result = new LinkedHashMap<>();
        for (SkillDefinition skill : skills) result.putIfAbsent(skill.getName(), skill);
        return result;
    }
}
