package com.flocier.domain.agent.runtime.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 默认 skill 注册中心。
 */
@Slf4j
@Component
public class DefaultSkillRegistry implements SkillRegistry {

    private final SkillRuntimeOptions skillRuntimeOptions;
    private final SkillParser skillParser;
    private final SkillPathGuard skillPathGuard;

    private volatile Map<String, SkillDefinition> skillMap = new LinkedHashMap<>();
    private volatile List<Path> allowedRoots = new ArrayList<>();
    private volatile List<Map<String, Object>> validationReports = new ArrayList<>();

    public DefaultSkillRegistry(SkillRuntimeOptions skillRuntimeOptions,
                                SkillParser skillParser,
                                SkillPathGuard skillPathGuard) {
        this.skillRuntimeOptions = skillRuntimeOptions;
        this.skillParser = skillParser;
        this.skillPathGuard = skillPathGuard;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    @Override
    public synchronized void refresh() {
        Map<String, SkillDefinition> refreshed = new LinkedHashMap<>();
        List<Path> roots = new ArrayList<>();
        List<Map<String, Object>> reports = new ArrayList<>();
        if (!skillRuntimeOptions.isEnabled()) {
            this.skillMap = refreshed;
            this.allowedRoots = roots;
            this.validationReports = reports;
            return;
        }
        for (String directory : skillRuntimeOptions.getDirectories()) {
            if (!StringUtils.hasText(directory)) {
                continue;
            }
            Path root = SkillPathGuard.resolveConfiguredRoot(directory);
            roots.add(root);
            if (!Files.isDirectory(root)) {
                log.info("skill root not found, skip scan. root={}", root);
                continue;
            }
            try (var stream = Files.list(root)) {
                List<Path> skillDirs = stream
                        .filter(Files::isDirectory)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
                for (Path skillDir : skillDirs) {
                    try {
                        SkillDefinition definition = skillParser.parse(skillDir, skillRuntimeOptions);
                        if (!definition.isEnabled()) {
                            reports.add(report(skillDir, definition.getName(), "disabled", "Skill is disabled."));
                            continue;
                        }
                        validateDefinition(definition);
                        String name = definition.getName();
                        if (refreshed.containsKey(name)) {
                            reports.add(report(skillDir, name, "rejected", "Duplicate skill name detected: " + name));
                            continue;
                        }
                        refreshed.put(name, definition);
                        reports.add(report(skillDir, name, "compatible", "Skill loaded."));
                    } catch (Exception e) {
                        reports.add(report(skillDir, skillDir.getFileName().toString(), "rejected", e.getMessage()));
                        log.warn("skill package skipped, dir={}, error={}", skillDir, e.getMessage());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to scan skill directory: " + root, e);
            }
        }
        this.skillMap = refreshed;
        this.allowedRoots = roots;
        this.validationReports = reports;
        log.info("skill registry refreshed, totalSkills={}, roots={}", refreshed.size(), roots);
    }

    @Override
    public List<SkillDefinition> listSkills() {
        return new ArrayList<>(skillMap.values());
    }

    @Override
    public Optional<SkillDefinition> findSkill(String skillName) {
        if (!StringUtils.hasText(skillName)) {
            return Optional.empty();
        }
        SkillDefinition direct = skillMap.get(skillName);
        if (direct != null) {
            return Optional.of(direct);
        }
        return skillMap.values().stream()
                .filter(item -> skillName.equals(item.getSkillId()))
                .findFirst();
    }

    @Override
    public SkillDefinition getRequiredSkill(String skillName) {
        return findSkill(skillName)
                .orElseThrow(() -> new IllegalStateException("Skill not found: " + skillName));
    }

    @Override
    public SkillScriptDefinition getRequiredScript(String skillName, String scriptName) {
        SkillDefinition definition = getRequiredSkill(skillName);
        return definition.getScripts().stream()
                .filter(item -> scriptName.equals(item.getScriptName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Skill script not found: " + skillName + "/" + scriptName));
    }

    @Override
    public Path assertPathAllowed(Path path) {
        return skillPathGuard.assertPathAllowed(path, allowedRoots);
    }

    @Override
    public String buildSkillDescription() {
        return listSkills().stream()
                .limit(skillRuntimeOptions.getMaxListEntries())
                .map(skill -> "- name: " + skill.getName()
                        + "\n  title: " + safe(skill.getTitle())
                        + "\n  description: " + safe(skill.getDescription())
                        + "\n  scenario: " + safe(skill.getScenario())
                        + "\n  version: " + safe(skill.getVersion())
                        + "\n  compatibility: " + safe(skill.getCompatibilityStatus())
                        + "\n  scripts: " + scriptSummaries(skill)
                        + "\n  tools: " + String.join(", ", skill.getToolRequirements()))
                .collect(Collectors.joining("\n"));
    }

    @Override
    public List<String> listPackageSources() {
        return allowedRoots.stream()
                .map(path -> path.getFileName() == null ? path.toString() : "runtime/skills")
                .distinct()
                .toList();
    }

    @Override
    public List<Map<String, Object>> listValidationReports() {
        return validationReports.stream()
                .map(item -> new LinkedHashMap<String, Object>(item))
                .collect(Collectors.toList());
    }

    private void validateDefinition(SkillDefinition definition) {
        if (!StringUtils.hasText(definition.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        if (!StringUtils.hasText(definition.getDescription())) {
            throw new IllegalArgumentException("description is required");
        }
    }

    private Map<String, Object> report(Path packageDir, String skillName, String status, String summary) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("packagePath", packageDir.toString());
        report.put("skillName", skillName);
        report.put("status", status);
        report.put("summary", summary);
        return report;
    }

    private String scriptSummaries(SkillDefinition skill) {
        if (skill.getScripts() == null || skill.getScripts().isEmpty()) {
            return "";
        }
        return skill.getScripts().stream()
                .map(script -> script.getScriptName() + "(" + safe(script.getRuntime()) + ")")
                .collect(Collectors.joining(", "));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

}
