package com.flocier.domain.agent.runtime.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 按参考项目的名称、描述和标签规则检索已注册 skill。 */
public final class SkillCatalogSearch {

    private SkillCatalogSearch() {
    }

    public record Hit(SkillDefinition skill, double score) {
    }

    public static List<Hit> search(List<SkillDefinition> skills, String query, String category, int limit) {
        String text = query == null ? "" : query.trim();
        String filter = category == null ? "" : category.trim();
        List<String> tokens = tokenize(text);
        List<Hit> hits = new ArrayList<>();
        for (SkillDefinition skill : skills == null ? List.<SkillDefinition>of() : skills) {
            if (skill == null || !skill.isEnabled() || skill.getName() == null) continue;
            if (!filter.isEmpty() && !filter.equalsIgnoreCase(skill.getCategory())) continue;
            double score = score(skill, text, tokens);
            if (text.isEmpty() || score > 0) hits.add(new Hit(skill, text.isEmpty() ? 0.1 : score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(hit -> hit.skill().getName(), String.CASE_INSENSITIVE_ORDER));
        return hits.subList(0, Math.min(hits.size(), limit <= 0 ? 10 : Math.min(limit, 25)));
    }

    private static double score(SkillDefinition skill, String text, List<String> tokens) {
        String name = empty(skill.getName()).toLowerCase(Locale.ROOT);
        String description = empty(skill.getDescription()).toLowerCase(Locale.ROOT);
        String query = text.toLowerCase(Locale.ROOT);
        if (!text.isEmpty() && skill.getName().equalsIgnoreCase(text)) return 1.0;
        if (!query.isEmpty() && name.contains(query)) return 0.9;
        for (String tag : tags(skill)) {
            if (tag != null && tag.equalsIgnoreCase(text)) return 0.85;
        }
        if (tokens.isEmpty()) return 0;
        int matched = 0;
        for (String token : tokens) {
            boolean inTag = tags(skill).stream()
                    .anyMatch(tag -> tag != null && tag.toLowerCase(Locale.ROOT).contains(token));
            if (name.contains(token) || description.contains(token) || inTag) matched++;
        }
        if (matched == tokens.size()) return 0.7;
        return matched > 0 ? 0.35 + (0.25 * matched / tokens.size()) : 0;
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (!part.isBlank()) tokens.add(part.trim());
        }
        return tokens;
    }

    private static List<String> tags(SkillDefinition skill) {
        return skill.getTags() == null ? List.of() : skill.getTags();
    }

    private static String empty(String text) {
        return text == null ? "" : text;
    }
}
