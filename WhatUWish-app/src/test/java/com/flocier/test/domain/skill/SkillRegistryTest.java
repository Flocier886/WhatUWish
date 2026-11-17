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

public class SkillRegistryTest {

    @Test
    public void shouldLoadWorkspaceReadmeSummarizerSkill() {
        SkillRuntimeOptions options = SkillRuntimeOptions.builder()
                .enabled(true)
                .directories(List.of(resolveSkillRoot().toString()))
                .reactEnabled(true)
                .workflowEnabled(false)
                .maxReadChars(16000)
                .maxListEntries(20)
                .maxSearchMatches(20)
                .maxExecutionSteps(3)
                .defaultScriptTimeoutSeconds(30)
                .build();

        DefaultSkillRegistry registry = new DefaultSkillRegistry(
                options,
                new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()),
                new SkillPathGuard()
        );
        registry.refresh();

        SkillDefinition definition = registry.getRequiredSkill("workspace-readme-summarizer");
        Assert.assertEquals("workspace-readme-summarizer", definition.getSkillId());
        Assert.assertTrue(definition.getToolRequirements().contains("file_read"));
        Assert.assertTrue(definition.getToolRequirements().contains("artifact_save"));
        Assert.assertTrue(definition.getPromptTemplates().containsKey("system"));
        Assert.assertNull(definition.getContent());
        Assert.assertTrue(java.nio.file.Files.exists(definition.getBasePath().resolve("SKILL.md")));
        Assert.assertTrue(registry.assertPathAllowed(resolveSkillRoot()
                        .resolve("workspace-readme-summarizer")
                        .resolve("SKILL.md"))
                .toString()
                .contains("workspace-readme-summarizer"));
    }

    private Path resolveSkillRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        if (current.getFileName() != null && "WhatUWish-app".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        return current.resolve("runtime").resolve("skills").normalize();
    }
}
