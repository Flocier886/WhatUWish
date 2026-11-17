package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.skill.DefaultSkillParser;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillMarkdownParser;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillScriptDefinition;
import com.flocier.domain.agent.runtime.skill.SkillYamlParser;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class SkillPackageParserTest {

    @Test
    public void shouldParseRequiredMetadataAndRegisteredScripts() throws Exception {
        DefaultSkillParser parser = new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser());
        SkillDefinition definition = parser.parse(resolveSkillRoot().resolve("community-script-fixture"), options());

        Assert.assertEquals("community-script-fixture", definition.getSkillId());
        Assert.assertEquals("local-fixture", definition.getOwner());
        Assert.assertTrue(definition.isEnabled());
        Assert.assertEquals("compatible", definition.getCompatibilityStatus());
        Assert.assertFalse(definition.getInstructionSummary().isBlank());
        Assert.assertEquals(3, definition.getScripts().size());

        SkillScriptDefinition summarize = definition.getScripts().stream()
                .filter(script -> "summarize".equals(script.getScriptName()))
                .findFirst()
                .orElse(null);
        Assert.assertNotNull(summarize);
        Assert.assertEquals("python", summarize.getRuntime());
        Assert.assertEquals("scripts/summarize.py", summarize.getRelativePath());
        Assert.assertEquals("scripts_yaml", summarize.getDeclarationSource());
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
