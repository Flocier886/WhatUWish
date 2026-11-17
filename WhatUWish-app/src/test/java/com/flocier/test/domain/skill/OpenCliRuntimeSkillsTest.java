package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillViewTool;
import com.flocier.domain.agent.runtime.skill.DefaultSkillParser;
import com.flocier.domain.agent.runtime.skill.DefaultSkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillMarkdownParser;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillYamlParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class OpenCliRuntimeSkillsTest {
    private static final List<String> NAMES = List.of("opencli-usage", "opencli-browser", "smart-search");
    private static final List<String> SEARCH_REFERENCES = List.of("sources-ai.md", "sources-tech.md",
            "sources-social.md", "sources-media.md", "sources-info.md", "sources-shopping.md",
            "sources-travel.md", "sources-other.md");

    @Test
    public void registeredSkillsExposeAdaptedMasterDocumentsAndSourceReferences() throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        if (root.getFileName().toString().equals("WhatUWish-app")) root = root.getParent();
        root = root.resolve("runtime/skills");
        SkillRuntimeOptions options = SkillRuntimeOptions.builder().enabled(true)
                .directories(List.of(root.toString())).maxReadChars(16000).build();
        SkillPathGuard guard = new SkillPathGuard();
        DefaultSkillRegistry registry = new DefaultSkillRegistry(options,
                new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()), guard);
        registry.refresh();
        SkillViewTool view = new SkillViewTool(registry, guard);
        String index = view.promptIndex();

        for (String name : NAMES) {
            var skill = registry.getRequiredSkill(name);
            assertTrue(index.contains(name));
            String document = Files.readString(skill.getBasePath().resolve("SKILL.md"));
            assertTrue(document.contains("browser"));
            assertTrue(document.contains("agent_browser"));
            assertFalse(document.contains("references/upstream.md"));
            assertFalse(document.contains("Bash(opencli:*)"));
            assertFalse(Files.exists(skill.getBasePath().resolve("references/upstream.md")));
            ToolResultPayload main = (ToolResultPayload) view.execute(request(name, null),
                    AgentContext.builder().build());
            assertTrue(main.getLlmObservation(), main.isSuccess());
            String body = document.substring(document.indexOf("---", 3) + 3).trim();
            assertTrue("主文档尾段未返回: " + name, main.getLlmObservation().contains(
                    body.substring(Math.max(0, body.length() - 100))));
        }
        assertTrue(Files.readString(registry.getRequiredSkill("opencli-usage").getBasePath()
                .resolve("SKILL.md")).contains("## The three pillars"));
        assertTrue(Files.readString(registry.getRequiredSkill("opencli-browser").getBasePath()
                .resolve("SKILL.md")).contains("## Target contract"));
        assertTrue(Files.readString(registry.getRequiredSkill("smart-search").getBasePath()
                .resolve("SKILL.md")).contains("## 主路由规则"));
        var search = registry.getRequiredSkill("smart-search");
        for (String file : SEARCH_REFERENCES) {
            String path = "references/" + file;
            assertTrue("未登记来源资料: " + path, search.getReferences().contains(path));
            ToolResultPayload source = (ToolResultPayload) view.execute(request("smart-search", path),
                    AgentContext.builder().build());
            assertTrue(path + ": " + source.getLlmObservation(), source.isSuccess());
        }
    }

    private ToolInvokeRequest request(String name, String file) {
        Map<String, Object> args = file == null ? Map.of("name", name)
                : Map.of("name", name, "file_path", file);
        return ToolInvokeRequest.builder().toolName("skill_view").arguments(args).build();
    }
}
