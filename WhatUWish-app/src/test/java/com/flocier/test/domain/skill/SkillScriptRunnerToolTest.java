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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkillScriptRunnerToolTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldRunRegisteredScriptByNameOnly() {
        SkillRuntimeOptions options = options();
        SkillScriptRunnerTool tool = new SkillScriptRunnerTool(
                "skill_script_runner_tool",
                "run registered skill script",
                Map.of(),
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
                                "skill_name", "master-style-fixture",
                                "script_name", "summarize",
                                "arguments", Map.of("text", "hello")
                        ))
                        .build(),
                AgentContext.builder().build()
        );

        Assert.assertTrue(payload.isSuccess());
        Map<String, Object> output = (Map<String, Object>) payload.getStructuredOutput();
        Assert.assertEquals("master-style-fixture", output.get("skillName"));
        Assert.assertEquals("summarize", output.get("scriptName"));
        Assert.assertTrue(String.valueOf(output.get("scriptPath")).endsWith("scripts/summarize.py"));
        Assert.assertTrue(payload.getLlmObservation().contains("stdout says hello"));
        Assert.assertTrue(payload.getLlmObservation().contains("artifact://summary.md"));
    }

    @Test
    public void shouldRejectUnknownScript() {
        SkillRuntimeOptions options = options();
        SkillScriptRunnerTool tool = new SkillScriptRunnerTool(
                "skill_script_runner_tool",
                "run registered skill script",
                Map.of(),
                registry(options),
                options,
                "http://localhost:18081",
                "/api/v1/tools/execute",
                new FakePythonToolClient()
        );
        ToolResultPayload payload = (ToolResultPayload) tool.execute(
                ToolInvokeRequest.builder()
                        .toolName("skill_script_runner_tool")
                        .arguments(Map.of("skill_name", "master-style-fixture", "script_name", "missing"))
                        .build(),
                AgentContext.builder().build()
        );
        Assert.assertFalse(payload.isSuccess());
        Assert.assertEquals("skill_script_runner_failed", payload.getErrorCode());
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

    private static class FakePythonToolClient extends PythonToolClient {
        @Override
        public PythonToolResponseEntity execute(String baseUrl, String endpoint, String toolName,
                                                ToolInvokeRequest request, AgentContext context) {
            Map<String, Object> args = request.getArguments();
            PythonToolResponseEntity response = new PythonToolResponseEntity();
            response.setSuccess(true);
            response.setStatus("success");
            response.setOutput("ok");
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("skillName", args.get("skillName"));
            structured.put("scriptName", args.get("actionName"));
            structured.put("scriptPath", args.get("scriptPath"));
            structured.put("runtime", args.get("runtime"));
            structured.put("success", true);
            structured.put("exitCode", 0);
            structured.put("summary", "script summary");
            structured.put("stdout", "stdout says hello");
            structured.put("stderr", "");
            structured.put("artifactRefs", List.of(Map.of("artifactRef", "artifact://summary.md")));
            response.setStructured(structured);
            return response;
        }
    }
}
