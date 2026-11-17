package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillGlobTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillGrepTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillListDirectoryTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillReadTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillTool;
import com.flocier.domain.agent.runtime.skill.DefaultSkillParser;
import com.flocier.domain.agent.runtime.skill.DefaultSkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillMarkdownParser;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillScriptDefinition;
import com.flocier.domain.agent.runtime.skill.SkillYamlParser;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** 验证从社区迁入的真实 skill 包能够直接进入 master-style 运行链。 */
public class CommunitySkillCompatibilityTest {

    private static final String SKILL_NAME = "github-deep-research";

    @Test
    @SuppressWarnings("unchecked")
    public void shouldLoadAndExploreMigratedGithubDeepResearchPackage() {
        SkillRuntimeOptions options = options();
        SkillPathGuard guard = new SkillPathGuard();
        DefaultSkillRegistry registry = registry(options, guard);
        AgentContext context = AgentContext.builder().build();

        SkillDefinition skill = registry.getRequiredSkill(SKILL_NAME);
        Assert.assertEquals("compatible", skill.getCompatibilityStatus());
        SkillScriptDefinition script = skill.getScripts().stream()
                .filter(item -> "github_api".equals(item.getScriptName()))
                .findFirst()
                .orElseThrow();
        Assert.assertEquals("python", script.getRuntime());
        Assert.assertEquals("scripts/github_api.py", script.getRelativePath());

        ToolResultPayload detail = execute(new SkillTool(
                "skill_tool", "skill discovery", Map.of(), registry, options
        ), Map.of("skill_name", SKILL_NAME, "include_references", true), context);
        Assert.assertTrue(detail.isSuccess());
        Map<String, Object> detailOutput = (Map<String, Object>) detail.getStructuredOutput();
        Assert.assertTrue(String.valueOf(detailOutput.get("content")).contains("Round 1 - GitHub API"));
        Assert.assertTrue(((List<?>) detailOutput.get("assets")).contains("assets/report_template.md"));
        Assert.assertTrue(detail.getLlmObservation().contains("skill_script_runner_tool"));

        ToolResultPayload list = execute(new SkillListDirectoryTool(
                "skill_list_directory_tool", "list", Map.of(), registry, options, guard
        ), Map.of("skill_name", SKILL_NAME, "path", ".", "max_depth", 2), context);
        Assert.assertTrue(list.isSuccess());
        Assert.assertTrue(list.getLlmObservation().contains("scripts/github_api.py"));
        Assert.assertTrue(list.getLlmObservation().contains("assets/report_template.md"));

        ToolResultPayload glob = execute(new SkillGlobTool(
                "skill_glob_tool", "glob", Map.of(), registry, options, guard
        ), Map.of("skill_name", SKILL_NAME, "path", ".", "pattern", "**/*.py"), context);
        Assert.assertTrue(glob.isSuccess());
        Assert.assertTrue(((List<?>) ((Map<String, Object>) glob.getStructuredOutput()).get("matches"))
                .contains("scripts/github_api.py"));

        ToolResultPayload grep = execute(new SkillGrepTool(
                "skill_grep_tool", "grep", Map.of(), registry, options, guard
        ), Map.of("skill_name", SKILL_NAME, "path", "SKILL.md", "pattern", "skill_script_runner_tool"), context);
        Assert.assertTrue(grep.isSuccess());
        Assert.assertFalse(((List<?>) ((Map<String, Object>) grep.getStructuredOutput()).get("matches")).isEmpty());

        ToolResultPayload read = execute(new SkillReadTool(
                "skill_read_tool", "read", Map.of(), registry, options, guard
        ), Map.of("skill_name", SKILL_NAME, "path", "assets/report_template.md", "line_count", 80), context);
        Assert.assertTrue(read.isSuccess());
        Assert.assertTrue(String.valueOf(((Map<String, Object>) read.getStructuredOutput()).get("content"))
                .contains("Executive Summary"));
    }

    private ToolResultPayload execute(BaseTool tool, Map<String, Object> arguments, AgentContext context) {
        ToolInvokeRequest request = ToolInvokeRequest.builder().toolName("test").arguments(arguments).build();
        return (ToolResultPayload) tool.execute(request, context);
    }

    private DefaultSkillRegistry registry(SkillRuntimeOptions options, SkillPathGuard guard) {
        DefaultSkillRegistry registry = new DefaultSkillRegistry(
                options,
                new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()),
                guard
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
                .maxSearchMatches(20)
                .defaultScriptTimeoutSeconds(30)
                .maxScriptTimeoutSeconds(120)
                .allowedScriptRuntimes(List.of("python", "node", "shell", "powershell", "bat"))
                .allowFullContentRead(true)
                .masterStyleToolsEnabled(true)
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
