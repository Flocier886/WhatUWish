package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolClient;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolResponseEntity;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillScriptRunnerTool;
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

public class SkillScriptRunnerResultTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldExposeStructuredScriptResultAndArtifactRefs() {
        SkillRuntimeOptions options = options();
        SkillScriptRunnerTool tool = new SkillScriptRunnerTool(
                "skill_script_runner_tool",
                "run registered script",
                Map.of("type", "object"),
                registry(options),
                options,
                "http://localhost:18081",
                "/api/v1/tools/execute",
                new FakePythonToolClient()
        );

        ToolResultPayload payload = (ToolResultPayload) tool.execute(
                ToolInvokeRequest.builder()
                        .toolName("skill_script_runner_tool")
                        .arguments(Map.of(
                                "skill_name", "community-script-fixture",
                                "script_name", "summarize",
                                "arguments", Map.of("text", "hello")
                        ))
                        .build(),
                AgentContext.builder().build()
        );

        Assert.assertTrue(payload.isSuccess());
        Assert.assertEquals("success", payload.getStatus());
        Assert.assertEquals("community-script-fixture", ((Map<String, Object>) payload.getStructuredOutput()).get("skillName"));
        Assert.assertEquals("summarize", ((Map<String, Object>) payload.getStructuredOutput()).get("scriptName"));
        Assert.assertEquals(List.of("file://output/summary.md"), payload.getArtifactRefs());
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

    private static class FakePythonToolClient extends PythonToolClient {
        @Override
        public PythonToolResponseEntity execute(String baseUrl,
                                                String endpoint,
                                                String toolName,
                                                ToolInvokeRequest request,
                                                AgentContext context) {
            PythonToolResponseEntity response = new PythonToolResponseEntity();
            response.setSuccess(true);
            response.setStatus("success");
            response.setOutput("ok");
            response.setLlmObservation("脚本执行成功。");
            response.setStructured(Map.of(
                    "skillName", "community-script-fixture",
                    "scriptName", "summarize",
                    "artifactRefs", List.of(Map.of("artifactRef", "file://output/summary.md"))
            ));
            return response;
        }
    }
}
