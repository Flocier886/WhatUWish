package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.DefaultToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import com.flocier.domain.agent.runtime.skill.DefaultSkillParser;
import com.flocier.domain.agent.runtime.skill.DefaultSkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillExecutionResult;
import com.flocier.domain.agent.runtime.skill.SkillExecutor;
import com.flocier.domain.agent.runtime.skill.SkillMarkdownParser;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillYamlParser;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillExecuteTool;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class SkillExecuteToolTest {

    @Test
    public void shouldExecuteSkillThroughAllowedToolSet() {
        DefaultSkillRegistry registry = newRegistry();
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.toolAsk(Mockito.any(ToolAskRequest.class)))
                .thenReturn(firstResponse())
                .thenReturn(secondResponse("README has been read and summarized."));

        SkillExecutor skillExecutor = new SkillExecutor(
                registry,
                newOptions(),
                llm,
                new DefaultToolRunner()
        );
        SkillExecuteTool tool = new SkillExecuteTool(
                "skill_execute_tool",
                "execute skill",
                Map.of("type", "object"),
                skillExecutor
        );

        ToolCollection toolCollection = new ToolCollection(List.of(
                new FakeFileReadTool(),
                tool
        ));

        AgentContext context = AgentContext.builder()
                .requestId("req-skill-1")
                .sessionId("session-skill-1")
                .traceId("trace-skill-1")
                .query("Read the README and summarize it.")
                .task("Read the README and summarize it.")
                .toolCollection(toolCollection)
                .attributes(new java.util.concurrent.ConcurrentHashMap<>())
                .build();
        context.setAttribute("runId", "run-skill-1");
        context.setAttribute("reactModel", "default-chat");

        SkillExecutionResult result = (SkillExecutionResult) tool.execute(
                ToolInvokeRequest.builder()
                        .toolName("skill_execute_tool")
                        .toolCallId("skill-call-1")
                        .arguments(Map.of(
                                "skill_name", "workspace-readme-summarizer",
                                "task", "Read the README and generate a summary.",
                                "arguments", Map.of(
                                        "readme_path", "WhatUWish-tools-python/README.md"
                                )
                        ))
                        .build(),
                context
        );

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("workspace-readme-summarizer", result.getSkillName());
        Assert.assertTrue(result.getUsedTools().contains("file_read"));
        Assert.assertTrue(result.getFinalAnswer().contains("README"));
        Assert.assertNotNull(result.getLlmObservation());
        Assert.assertFalse(result.getLedgerFacts().isEmpty());
    }

    @Test
    public void shouldWriteToolOwnedObservationIntoSkillSubspace() {
        DefaultSkillRegistry registry = newRegistry();
        LLM llm = Mockito.mock(LLM.class);
        ArgumentCaptor<ToolAskRequest> captor = ArgumentCaptor.forClass(ToolAskRequest.class);
        Mockito.when(llm.toolAsk(captor.capture()))
                .thenReturn(firstResponse())
                .thenReturn(secondResponse("Switch to directory exploration after the failed file read."));

        SkillExecutor skillExecutor = new SkillExecutor(
                registry,
                newOptions(),
                llm,
                new DefaultToolRunner()
        );
        SkillExecuteTool tool = new SkillExecuteTool(
                "skill_execute_tool",
                "execute skill",
                Map.of("type", "object"),
                skillExecutor
        );

        ToolCollection toolCollection = new ToolCollection(List.of(
                new FakeMissingFileReadTool(),
                tool
        ));

        AgentContext context = AgentContext.builder()
                .requestId("req-skill-2")
                .sessionId("session-skill-2")
                .traceId("trace-skill-2")
                .query("Read a missing README and recover.")
                .task("Read a missing README and recover.")
                .toolCollection(toolCollection)
                .attributes(new java.util.concurrent.ConcurrentHashMap<>())
                .build();
        context.setAttribute("runId", "run-skill-2");
        context.setAttribute("reactModel", "default-chat");

        SkillExecutionResult result = (SkillExecutionResult) tool.execute(
                ToolInvokeRequest.builder()
                        .toolName("skill_execute_tool")
                        .toolCallId("skill-call-2")
                        .arguments(Map.of(
                                "skill_name", "workspace-readme-summarizer",
                                "task", "Read README from a wrong path, then decide the next step.",
                                "arguments", Map.of(
                                        "readme_path", "WhatUWish-tools-python/README.md"
                                )
                        ))
                        .build(),
                context
        );

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(2, captor.getAllValues().size());
        List<LlmMessage> secondRequestMessages = captor.getAllValues().get(1).getMessages();
        LlmMessage toolMessage = secondRequestMessages.stream()
                .filter(message -> message.getRole() == LlmMessageRole.TOOL)
                .findFirst()
                .orElseThrow();
        Assert.assertTrue(toolMessage.getContent().contains("directory_list"));
        Assert.assertTrue(toolMessage.getContent().contains("file_search"));
    }

    private DefaultSkillRegistry newRegistry() {
        DefaultSkillRegistry registry = new DefaultSkillRegistry(
                newOptions(),
                new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()),
                new SkillPathGuard()
        );
        registry.refresh();
        return registry;
    }

    private SkillRuntimeOptions newOptions() {
        return SkillRuntimeOptions.builder()
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
    }

    private ToolAskResponse firstResponse() {
        return ToolAskResponse.builder()
                .completed(false)
                .thought("Read the README first.")
                .toolCalls(List.of(
                        ToolCall.builder()
                                .id("tool-call-1")
                                .type("function")
                                .function(ToolCall.Function.builder()
                                        .name("file_read")
                                        .arguments("{\"path\":\"WhatUWish-tools-python/README.md\"}")
                                        .build())
                                .build()
                ))
                .build();
    }

    private ToolAskResponse secondResponse(String thought) {
        return ToolAskResponse.builder()
                .completed(true)
                .thought(thought)
                .toolCalls(List.of())
                .build();
    }

    private Path resolveSkillRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        if (current.getFileName() != null && "WhatUWish-app".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        return current.resolve("runtime").resolve("skills").normalize();
    }

    private static class FakeFileReadTool implements BaseTool {

        @Override
        public String name() {
            return "file_read";
        }

        @Override
        public String description() {
            return "read file";
        }

        @Override
        public Object params() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return ToolResultPayload.builder()
                    .success(true)
                    .status("success")
                    .toolResult("README content")
                    .llmObservation("已成功读取 README 内容。")
                    .structuredOutput(Map.of("content", "README content"))
                    .ledgerFacts(List.of(Map.of("factType", "file_read")))
                    .build();
        }
    }

    private static class FakeMissingFileReadTool implements BaseTool {

        @Override
        public String name() {
            return "file_read";
        }

        @Override
        public String description() {
            return "read file";
        }

        @Override
        public Object params() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return ToolResultPayload.builder()
                    .success(false)
                    .status("failed")
                    .toolResult("file read failed")
                    .llmObservation("文件读取失败：目标文件不存在。建议先调用 directory_list 或 file_search 确认真正的 README 路径。")
                    .errorCode("file_not_found")
                    .errorMessage("File not found: WhatUWish-tools-python/README.md")
                    .ledgerFacts(List.of(Map.of("factType", "file_read_failed", "failureType", "not_found")))
                    .build();
        }
    }
}
