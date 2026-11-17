package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillTool;
import com.flocier.domain.agent.runtime.skill.DefaultSkillParser;
import com.flocier.domain.agent.runtime.skill.DefaultSkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillMarkdownParser;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillYamlParser;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class SkillToolDiscoveryTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReturnScenarioVersionConstraintsScriptsAndCompatibility() {
        SkillRuntimeOptions options = options();
        SkillTool tool = new SkillTool(
                "skill_tool",
                "skill discovery",
                Map.of("type", "object"),
                registry(options),
                options
        );

        ToolResultPayload payload = (ToolResultPayload) tool.execute(
                ToolInvokeRequest.builder().toolName("skill_tool").arguments(Map.of()).build(),
                AgentContext.builder().build()
        );
        Map<String, Object> output = (Map<String, Object>) payload.getStructuredOutput();
        List<Map<String, Object>> skills = (List<Map<String, Object>>) output.get("skills");
        Map<String, Object> fixture = skills.stream()
                .filter(item -> "community-script-fixture".equals(item.get("name")))
                .findFirst()
                .orElseThrow();

        Assert.assertEquals("compatible", fixture.get("compatibilityStatus"));
        Assert.assertNotNull(fixture.get("scenario"));
        Assert.assertNotNull(fixture.get("version"));
        Assert.assertFalse(((List<?>) fixture.get("scripts")).isEmpty());
    }

    private DefaultSkillRegistry registry(SkillRuntimeOptions options) {
        DefaultSkillRegistry registry = new DefaultSkillRegistry(
                options,
                new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()),
                new SkillPathGuard()
        );
        registry.refresh();
        return registry;
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
