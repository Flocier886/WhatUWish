package com.flocier.domain.agent.runtime.skill;

import org.springframework.beans.factory.annotation.Autowired;
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
 * 默认 skill 解析器。
 *
 * <p>本轮以 master 风格为默认：`SKILL.md` 是必需入口，`skill.yaml` 是兼容增强，
 * `scripts/` 和 `scripts.yaml` 会被归一为注册脚本。</p>
 */
@Component
public class DefaultSkillParser implements SkillParser {

    private final SkillYamlParser skillYamlParser;
    private final SkillMarkdownParser skillMarkdownParser;
    private final SkillScriptDiscoverer skillScriptDiscoverer;
    private final SkillPathGuard skillPathGuard;

    @Autowired
    public DefaultSkillParser(SkillYamlParser skillYamlParser,
                              SkillMarkdownParser skillMarkdownParser,
                              SkillScriptDiscoverer skillScriptDiscoverer,
                              SkillPathGuard skillPathGuard) {
        this.skillYamlParser = skillYamlParser;
        this.skillMarkdownParser = skillMarkdownParser;
        this.skillScriptDiscoverer = skillScriptDiscoverer;
        this.skillPathGuard = skillPathGuard;
    }

    public DefaultSkillParser(SkillYamlParser skillYamlParser,
                              SkillMarkdownParser skillMarkdownParser) {
        this(skillYamlParser, skillMarkdownParser, new SkillScriptDiscoverer(), new SkillPathGuard());
    }

    @Override
    public SkillDefinition parse(Path skillDirectory, SkillRuntimeOptions options) throws IOException {
        Path markdownPath = skillDirectory.resolve("SKILL.md");
        if (!Files.exists(markdownPath)) {
            throw new IllegalStateException("Missing SKILL.md: " + skillDirectory);
        }

        SkillMarkdownParser.ParsedSkillMarkdown markdown =
                skillMarkdownParser.parseWithFrontMatter(markdownPath, 0);
        SkillYamlParser.ParsedSkillYaml parsedYaml = parseOptionalYaml(skillDirectory.resolve("skill.yaml"));

        Map<String, String> markdownMeta = markdown.getFrontMatter();
        Map<String, String> yamlScalars = parsedYaml.getScalars();
        Map<String, SkillScriptDefinition> discoveredScripts =
                skillScriptDiscoverer.discover(skillDirectory, options, skillPathGuard);

        String skillName = firstNonBlank(markdownMeta.get("name"), yamlScalars.get("name"), skillDirectory.getFileName().toString());
        String description = firstNonBlank(markdownMeta.get("description"), yamlScalars.get("description"));
        return SkillDefinition.builder()
                .skillId(firstNonBlank(yamlScalars.get("skillId"), skillName))
                .name(skillName)
                .title(firstNonBlank(markdownMeta.get("title"), yamlScalars.get("title"), skillName))
                .description(description)
                .category(firstNonBlank(markdownMeta.get("category"), yamlScalars.get("category"), "general"))
                .scenario(firstNonBlank(markdownMeta.get("scenario"), yamlScalars.get("scenario"), description))
                .version(firstNonBlank(markdownMeta.get("version"), yamlScalars.get("version"), "1.0.0"))
                .sourceId("runtime/skills")
                .owner(firstNonBlank(markdownMeta.get("owner"), yamlScalars.get("owner")))
                .basePath(skillDirectory.toAbsolutePath().normalize())
                .content(null)
                .instructionSummary(skillMarkdownParser.summarize(markdown.getBody(), description, 300))
                .contentRef(skillDirectory.resolve("SKILL.md").toString())
                .promptTemplates(readPromptTemplates(skillDirectory, parsedYaml))
                .toolRequirements(copyList(parsedYaml.getLists().get("toolRequirements")))
                .tags(copyList(parsedYaml.getLists().get("tags")))
                .constraints(copyList(parsedYaml.getLists().get("constraints")))
                .inputSchema(parsedYaml.scalarJsonMap("inputSchema"))
                .outputSchema(parsedYaml.scalarJsonMap("outputSchema"))
                .scripts(new ArrayList<>(discoveredScripts.values()))
                .references(listRelativePaths(skillDirectory, "references"))
                .assets(listRelativePaths(skillDirectory, "assets"))
                .enabled(!"false".equalsIgnoreCase(firstNonBlank(yamlScalars.get("enabled"), "true")))
                .compatibilityStatus("compatible")
                .build();
    }

    private SkillYamlParser.ParsedSkillYaml parseOptionalYaml(Path yamlPath) throws IOException {
        if (!Files.exists(yamlPath)) {
            return SkillYamlParser.ParsedSkillYaml.builder().build();
        }
        return skillYamlParser.parse(yamlPath);
    }

    private Map<String, String> readPromptTemplates(Path skillDirectory,
                                                   SkillYamlParser.ParsedSkillYaml parsed) throws IOException {
        Map<String, String> promptTemplates = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parsed.getPromptTemplatePaths().entrySet()) {
            if (!StringUtils.hasText(entry.getValue())) {
                continue;
            }
            Path promptPath = skillPathGuard.assertPathUnderRoot(skillDirectory.resolve(entry.getValue()), skillDirectory);
            promptTemplates.put(entry.getKey(), Files.readString(promptPath, StandardCharsets.UTF_8));
        }
        return promptTemplates;
    }

    private List<String> listRelativePaths(Path skillDirectory, String child) throws IOException {
        Path root = skillDirectory.resolve(child);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> skillDirectory.relativize(path).toString().replace('\\', '/'))
                    .toList();
        }
    }

    private List<String> copyList(List<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
