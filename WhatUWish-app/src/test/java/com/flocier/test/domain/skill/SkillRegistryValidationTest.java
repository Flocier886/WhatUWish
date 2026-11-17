package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.skill.DefaultSkillParser;
import com.flocier.domain.agent.runtime.skill.DefaultSkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillMarkdownParser;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillYamlParser;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class SkillRegistryValidationTest {

    @Test
    public void shouldFilterDisabledSkillsAndExposeValidationAccessors() {
        DefaultSkillRegistry registry = new DefaultSkillRegistry(
                options(),
                new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()),
                new SkillPathGuard()
        );
        registry.refresh();

        SkillDefinition fixture = registry.getRequiredSkill("community-script-fixture");
        Assert.assertTrue(fixture.isEnabled());
        Assert.assertEquals("runtime/skills", fixture.getSourceId());
        Assert.assertFalse(registry.listValidationReports().isEmpty());
        Assert.assertTrue(registry.listPackageSources().contains("runtime/skills"));

        SkillDefinition githubResearch = registry.getRequiredSkill("github-deep-research");
        Assert.assertTrue(githubResearch.isEnabled());
        Assert.assertEquals("python", registry.getRequiredScript("github-deep-research", "github_api").getRuntime());
        Assert.assertEquals("scripts/github_api.py", registry.getRequiredScript("github-deep-research", "github_api").getRelativePath());
    }

    private SkillRuntimeOptions options() {
        return SkillRuntimeOptions.builder()
                .enabled(true)
                .directories(List.of(resolveSkillRoot().toString()))
                .maxReadChars(16000)
                .maxListEntries(20)
                .maxExecutionSteps(3)
                .defaultScriptTimeoutSeconds(30)
                .maxScriptTimeoutSeconds(120)
                .allowedScriptRuntimes(List.of("python"))
                .scriptActionsEnabled(true)
                .compatibilityCheckEnabled(true)
                .allowFullContentRead(true)
                .build();
    }

    private Path resolveSkillRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        if (current.getFileName() != null && "WhatUWish-app".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        return current.resolve("runtime").resolve("skills").normalize();
    }
}
