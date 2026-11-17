package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillGlobTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillGrepTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillListDirectoryTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillReadTool;
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

public class SkillScopedPathToolTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReadListGlobAndGrepInsideSkillPackage() {
        SkillRuntimeOptions options = options();
        DefaultSkillRegistry registry = registry(options);
        SkillPathGuard guard = new SkillPathGuard();
        AgentContext context = AgentContext.builder().build();

        ToolResultPayload list = (ToolResultPayload) new SkillListDirectoryTool(
                "skill_list_directory_tool", "list", Map.of(), registry, options, guard
        ).execute(request(Map.of("skill_name", "master-style-fixture", "path", "references")), context);
        Assert.assertTrue(list.isSuccess());
        Assert.assertFalse(((List<?>) ((Map<String, Object>) list.getStructuredOutput()).get("entries")).isEmpty());
        Assert.assertTrue(list.getLlmObservation().contains("guide.md"));

        ToolResultPayload glob = (ToolResultPayload) new SkillGlobTool(
                "skill_glob_tool", "glob", Map.of(), registry, options, guard
        ).execute(request(Map.of("skill_name", "master-style-fixture", "path", ".", "pattern", "**/*.md")), context);
        Assert.assertTrue(glob.isSuccess());
        Assert.assertTrue(((List<?>) ((Map<String, Object>) glob.getStructuredOutput()).get("matches")).contains("references/guide.md"));
        Assert.assertTrue(glob.getLlmObservation().contains("references/guide.md"));

        ToolResultPayload grep = (ToolResultPayload) new SkillGrepTool(
                "skill_grep_tool", "grep", Map.of(), registry, options, guard
        ).execute(request(Map.of("skill_name", "master-style-fixture", "path", ".", "pattern", "lookup works")), context);
        Assert.assertTrue(grep.isSuccess());
        Assert.assertFalse(((List<?>) ((Map<String, Object>) grep.getStructuredOutput()).get("matches")).isEmpty());
        Assert.assertTrue(grep.getLlmObservation().contains("lookup works"));
        Assert.assertTrue(grep.getLlmObservation().contains("references/guide.md"));

        ToolResultPayload read = (ToolResultPayload) new SkillReadTool(
                "skill_read_tool", "read", Map.of(), registry, options, guard
        ).execute(request(Map.of("skill_name", "master-style-fixture", "path", "references/guide.md", "line_count", 20)), context);
        Assert.assertTrue(read.isSuccess());
        Assert.assertTrue(String.valueOf(((Map<String, Object>) read.getStructuredOutput()).get("content")).contains("master-style skill lookup works"));
        Assert.assertTrue(read.getLlmObservation().contains("master-style skill lookup works"));
    }

    @Test
    public void shouldRejectPathOutsideSkillPackage() {
        SkillRuntimeOptions options = options();
        ToolResultPayload payload = (ToolResultPayload) new SkillReadTool(
                "skill_read_tool", "read", Map.of(), registry(options), options, new SkillPathGuard()
        ).execute(request(Map.of("skill_name", "master-style-fixture", "path", "../README.md")), AgentContext.builder().build());
        Assert.assertFalse(payload.isSuccess());
        Assert.assertEquals("skill_read_failed", payload.getErrorCode());
    }

    private ToolInvokeRequest request(Map<String, Object> args) {
        return ToolInvokeRequest.builder().toolName("test").arguments(args).build();
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
                .maxSearchMatches(20)
                .defaultScriptTimeoutSeconds(30)
                .maxScriptTimeoutSeconds(120)
                .allowedScriptRuntimes(List.of("python"))
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
