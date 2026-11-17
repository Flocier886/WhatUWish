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

public class SkillToolTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldListAndReadSkillDetails() {
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
                .allowFullContentRead(true)
                .build();
        DefaultSkillRegistry registry = new DefaultSkillRegistry(
                options,
                new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()),
                new SkillPathGuard()
        );
        registry.refresh();

        SkillTool tool = new SkillTool(
                "skill_tool",
                "查看 skill 信息",
                Map.of("type", "object"),
                registry,
                options
        );

        ToolResultPayload listResult = (ToolResultPayload) tool.execute(
                ToolInvokeRequest.builder()
                        .toolName("skill_tool")
                        .arguments(Map.of())
                        .build(),
                AgentContext.builder().build()
        );
        Map<String, Object> listPayload = (Map<String, Object>) listResult.getStructuredOutput();
        Assert.assertTrue(((Number) listPayload.get("count")).intValue() >= 1);
        Assert.assertTrue(listResult.getLlmObservation().contains("workspace-readme-summarizer"));

        ToolResultPayload detailResult = (ToolResultPayload) tool.execute(
                ToolInvokeRequest.builder()
                        .toolName("skill_tool")
                        .arguments(Map.of(
                                "skill_name", "workspace-readme-summarizer",
                                "include_content", true
                        ))
                        .build(),
                AgentContext.builder().build()
        );
        Map<String, Object> detailPayload = (Map<String, Object>) detailResult.getStructuredOutput();
        Assert.assertEquals("workspace-readme-summarizer", detailPayload.get("name"));
        Assert.assertTrue(String.valueOf(detailPayload.get("content")).contains("技能定位"));
        Assert.assertTrue(detailResult.getLlmObservation().contains("技能定位"));
    }

    private Path resolveSkillRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        if (current.getFileName() != null && "WhatUWish-app".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        return current.resolve("runtime").resolve("skills").normalize();
    }
}
