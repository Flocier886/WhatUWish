package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.DefaultToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.McpToolAdapter;
import com.flocier.domain.agent.runtime.agent.tool.McpToolAccessor;
import com.flocier.domain.agent.runtime.agent.tool.mcp.McpToolDefinition;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolAdapter;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolClient;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolResponseEntity;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillExecuteTool;
import com.flocier.domain.agent.runtime.skill.SkillExecutionResult;
import com.flocier.domain.agent.runtime.skill.SkillExecutor;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

public class ToolRunnerTest {

    private final DefaultToolRunner toolRunner = new DefaultToolRunner();

    @Test
    public void shouldExecuteJavaToolAndUseToolOwnedObservation() {
        ToolCollection toolCollection = new ToolCollection(List.of(new CustomJavaTool()));

        ToolExecutionResult result = toolRunner.run(
                toolCollection,
                ToolInvokeRequest.builder()
                        .toolName("custom_java_tool")
                        .toolCallId("call-1")
                        .arguments(Map.of("message", "hello"))
                        .build(),
                AgentContext.builder().requestId("req-1").build()
        );

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("success", result.getStatus());
        Assert.assertEquals("custom_java_tool", result.getToolName());
        Assert.assertEquals("call-1", result.getToolCallId());
        Assert.assertEquals("java", result.getSourceType());
        Assert.assertEquals("hello", result.getArguments().get("message"));
        Assert.assertEquals("已完成自定义 Java 工具处理，可继续基于结果推进。", result.getLlmObservation());
        Assert.assertNotNull(result.getStructuredOutput());
        Assert.assertEquals(1, result.getLedgerFacts().size());
        Assert.assertNotNull(result.getStartedAt());
        Assert.assertNotNull(result.getFinishedAt());
        Assert.assertNotNull(result.getDurationMs());
    }

    @Test
    public void shouldReturnInfrastructureFailureWhenToolMissing() {
        ToolExecutionResult result = toolRunner.run(
                new ToolCollection(List.of()),
                ToolInvokeRequest.builder()
                        .toolName("missing_tool")
                        .toolCallId("call-2")
                        .build(),
                AgentContext.builder().build()
        );

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("failed", result.getStatus());
        Assert.assertEquals("tool_not_found", result.getErrorCode());
        Assert.assertTrue(result.getLlmObservation().contains("未找到工具"));
        Assert.assertFalse(result.getLedgerFacts().isEmpty());
    }

    @Test
    public void shouldMapPythonFailureIntoToolOwnedObservation() {
        PythonToolClient pythonToolClient = Mockito.mock(PythonToolClient.class);
        PythonToolResponseEntity pythonResponse = new PythonToolResponseEntity();
        pythonResponse.setSuccess(false);
        pythonResponse.setStatus("failed");
        pythonResponse.setErrorCode("file_not_found");
        pythonResponse.setErrorMessage("File not found: README2.md");
        pythonResponse.setLlmObservation("文件读取失败：目标文件不存在。建议先调用 directory_list 或 file_search 确认真实路径。");
        pythonResponse.setLedgerFacts(List.of(Map.of(
                "factType", "file_read_failed",
                "failureType", "not_found"
        )));
        Mockito.when(pythonToolClient.execute(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(ToolInvokeRequest.class),
                Mockito.any(AgentContext.class)
        )).thenReturn(pythonResponse);

        ToolCollection pythonTools = new ToolCollection(List.of(
                new PythonToolAdapter(
                        "file_read",
                        "read file from workspace",
                        Map.of("type", "object"),
                        "http://localhost:18081",
                        "/api/v1/tools/execute",
                        pythonToolClient
                )
        ));
        ToolExecutionResult pythonResult = toolRunner.run(
                pythonTools,
                ToolInvokeRequest.builder()
                        .toolName("file_read")
                        .toolCallId("call-py-fail")
                        .arguments(Map.of("path", "README2.md"))
                        .build(),
                AgentContext.builder().build()
        );

        Assert.assertEquals("python", pythonResult.getSourceType());
        Assert.assertFalse(pythonResult.isSuccess());
        Assert.assertEquals("failed", pythonResult.getStatus());
        Assert.assertEquals("file_not_found", pythonResult.getErrorCode());
        Assert.assertTrue(pythonResult.getLlmObservation().contains("directory_list"));
        Assert.assertTrue(pythonResult.getLlmObservation().contains("file_search"));
        Assert.assertEquals(1, pythonResult.getLedgerFacts().size());
        Assert.assertEquals("not_found", pythonResult.getLedgerFacts().get(0).get("failureType"));
    }

    @Test
    public void shouldKeepResearchToolSuccessAndControlledFailuresReadable() {
        PythonToolClient pythonToolClient = Mockito.mock(PythonToolClient.class);

        PythonToolResponseEntity searchSuccess = new PythonToolResponseEntity();
        searchSuccess.setSuccess(true);
        searchSuccess.setStatus("success");
        searchSuccess.setOutput("- Agent research guide (example.com): https://example.com/research");
        searchSuccess.setLlmObservation("网页搜索查询：Agent research\n返回 1 条候选结果：\n1. Agent research guide");
        searchSuccess.setStructured(Map.of("query", "Agent research", "results", List.of(Map.of(
                "title", "Agent research guide",
                "url", "https://example.com/research",
                "domain", "example.com",
                "snippet", "Research source",
                "provider", "tavily",
                "rank", 1
        ))));
        searchSuccess.setLedgerFacts(List.of(Map.of("factType", "web_search", "resultCount", 1)));

        PythonToolResponseEntity providerNotConfigured = new PythonToolResponseEntity();
        providerNotConfigured.setSuccess(false);
        providerNotConfigured.setStatus("failed");
        providerNotConfigured.setErrorCode("search_provider_not_configured");
        providerNotConfigured.setErrorMessage("网页搜索 Provider 未配置");
        providerNotConfigured.setLlmObservation("网页搜索 Provider 未配置，请设置 WEB_SEARCH_PROVIDER、WEB_SEARCH_API_BASE 和 WEB_SEARCH_API_KEY。");

        PythonToolResponseEntity fetchTargetRejected = new PythonToolResponseEntity();
        fetchTargetRejected.setSuccess(false);
        fetchTargetRejected.setStatus("failed");
        fetchTargetRejected.setErrorCode("fetch_target_not_allowed");
        fetchTargetRejected.setErrorMessage("web_fetch 不允许访问非公网目标地址");
        fetchTargetRejected.setLlmObservation("web_fetch 不允许访问非公网目标地址，请选择公开 HTTP(S) 来源。");

        Mockito.when(pythonToolClient.execute(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(ToolInvokeRequest.class),
                Mockito.any(AgentContext.class)
        )).thenReturn(searchSuccess, providerNotConfigured, fetchTargetRejected);

        ToolCollection tools = new ToolCollection(List.of(
                new PythonToolAdapter("web_search", "search public web sources", Map.of("type", "object"),
                        "http://localhost:18081", "/api/v1/tools/execute", pythonToolClient),
                new PythonToolAdapter("web_fetch", "fetch public web source", Map.of("type", "object"),
                        "http://localhost:18081", "/api/v1/tools/execute", pythonToolClient)
        ));
        AgentContext context = AgentContext.builder().requestId("research-tool-request").build();

        ToolExecutionResult searchResult = toolRunner.run(tools,
                ToolInvokeRequest.builder().toolName("web_search").toolCallId("call-search-success")
                        .arguments(Map.of("query", "Agent research", "limit", 1)).build(), context);
        ToolExecutionResult providerFailure = toolRunner.run(tools,
                ToolInvokeRequest.builder().toolName("web_search").toolCallId("call-search-config")
                        .arguments(Map.of("query", "Agent research")).build(), context);
        ToolExecutionResult fetchFailure = toolRunner.run(tools,
                ToolInvokeRequest.builder().toolName("web_fetch").toolCallId("call-fetch-ssrf")
                        .arguments(Map.of("url", "http://127.0.0.1/private")).build(), context);

        Assert.assertTrue(searchResult.isSuccess());
        Assert.assertEquals("web_search", searchResult.getToolName());
        Assert.assertTrue(searchResult.getLlmObservation().contains("Agent research guide"));
        Map<?, ?> structuredSearchResult = (Map<?, ?>) searchResult.getStructuredOutput();
        List<?> searchResults = (List<?>) structuredSearchResult.get("results");
        Assert.assertEquals(1, searchResults.size());
        Assert.assertEquals("https://example.com/research", ((Map<?, ?>) searchResults.get(0)).get("url"));

        Assert.assertFalse(providerFailure.isSuccess());
        Assert.assertEquals("search_provider_not_configured", providerFailure.getErrorCode());
        Assert.assertTrue(providerFailure.getLlmObservation().contains("WEB_SEARCH_API_KEY"));

        Assert.assertFalse(fetchFailure.isSuccess());
        Assert.assertEquals("fetch_target_not_allowed", fetchFailure.getErrorCode());
        Assert.assertTrue(fetchFailure.getLlmObservation().contains("公开 HTTP(S)"));
    }

    @Test
    public void shouldKeepPythonPartialSuccessDeclaredByTool() {
        PythonToolClient pythonToolClient = Mockito.mock(PythonToolClient.class);
        PythonToolResponseEntity pythonResponse = new PythonToolResponseEntity();
        pythonResponse.setSuccess(true);
        pythonResponse.setStatus("partial_success");
        pythonResponse.setOutput("No matches found");
        pythonResponse.setLlmObservation("搜索已执行完成，但没有找到目标内容。建议调整关键词或先查看目录结构。");
        pythonResponse.setStructured(Map.of("matches", List.of()));
        pythonResponse.setLedgerFacts(List.of(Map.of(
                "factType", "file_search_empty",
                "matchCount", 0
        )));
        Mockito.when(pythonToolClient.execute(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(ToolInvokeRequest.class),
                Mockito.any(AgentContext.class)
        )).thenReturn(pythonResponse);

        ToolCollection pythonTools = new ToolCollection(List.of(
                new PythonToolAdapter(
                        "file_search",
                        "search file content",
                        Map.of("type", "object"),
                        "http://localhost:18081",
                        "/api/v1/tools/execute",
                        pythonToolClient
                )
        ));
        ToolExecutionResult pythonResult = toolRunner.run(
                pythonTools,
                ToolInvokeRequest.builder()
                        .toolName("file_search")
                        .toolCallId("call-py-empty")
                        .arguments(Map.of("query", "ledger", "path", "docs"))
                        .build(),
                AgentContext.builder().build()
        );

        Assert.assertTrue(pythonResult.isSuccess());
        Assert.assertEquals("partial_success", pythonResult.getStatus());
        Assert.assertTrue(pythonResult.getLlmObservation().contains("调整关键词"));
        Assert.assertEquals(1, pythonResult.getLedgerFacts().size());
    }

    @Test
    public void shouldMapDeepSearchOutcomeAndArtifactThroughPythonAdapter() {
        PythonToolClient pythonToolClient = Mockito.mock(PythonToolClient.class);
        PythonToolResponseEntity response = new PythonToolResponseEntity();
        response.setSuccess(true);
        response.setStatus("partial_success");
        response.setLlmObservation("DeepSearch 已完成两轮证据驱动的研究，可直接基于结论继续交付。");
        response.setStructured(Map.of(
                "schemaVersion", "deep_search_result.v1",
                "researchSummary", Map.of("topic", "ReAct tool observation", "status", "partial_success",
                        "iterations", 2, "searchesExecuted", 2, "sourceCount", 3, "evidenceCount", 1,
                        "stopReason", "coverage_sufficient", "limitations", List.of()),
                "finalReport", Map.of("executiveSummary", "Tool observation must preserve decision-relevant facts.",
                        "answer", "# Research conclusion", "citations", List.of()),
                "iterations", List.of(Map.of("number", 1, "queries", List.of("ReAct observation"),
                        "sourceCount", 3, "evidenceCount", 1, "reviewAction", "finish", "reviewSummary", "covered")),
                "researchTrace", Map.of("schemaVersion", "researchTrace.v1", "phases", List.of(Map.of(
                        "stage", "plan", "status", "success", "internalCallId", "research.plan.1.call")))
        ));
        response.setLedgerFacts(List.of(Map.of(
                "kind", "deep_search.v1", "sourceCount", 3, "evidenceCount", 1, "stopReason", "coverage_sufficient"
        )));
        response.setArtifacts(List.of(Map.of(
                "artifactId", "artifact-research-1", "name", "deep-search-report.md",
                "artifactType", "deep_search_report", "storageRef", "artifacts/deep-search-report.md",
                "storageKey", "research/run-1/deep-search-report.md"
        )));
        Mockito.when(pythonToolClient.execute(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.any(ToolInvokeRequest.class), Mockito.any(AgentContext.class)
        )).thenReturn(response);

        ToolExecutionResult result = toolRunner.run(
                new ToolCollection(List.of(new PythonToolAdapter(
                        "deep_search_tool", "complete a multi-source deep research workflow", Map.of("type", "object"),
                        "http://localhost:18081", "/api/v1/tools/execute", pythonToolClient
                ))),
                ToolInvokeRequest.builder().toolName("deep_search_tool").toolCallId("call-research")
                        .arguments(Map.of("topic", "ReAct tool observation")).build(),
                AgentContext.builder().requestId("research-request").build()
        );

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("partial_success", result.getStatus());
        Assert.assertEquals("python", result.getSourceType());
        Assert.assertEquals(1, result.getArtifactRefs().size());
        Assert.assertEquals("artifacts/deep-search-report.md", result.getArtifactRefs().get(0));
        Assert.assertEquals(1, result.getLedgerFacts().size());
        Map<?, ?> structured = (Map<?, ?>) result.getStructuredOutput();
        Assert.assertEquals("deep_search_result.v1", structured.get("schemaVersion"));
        Assert.assertEquals("partial_success", ((Map<?, ?>) structured.get("researchSummary")).get("status"));
        Assert.assertEquals("researchTrace.v1", ((Map<?, ?>) structured.get("researchTrace")).get("schemaVersion"));
    }

    @Test
    public void shouldPreserveSourceTypeForPythonAndMcpTools() {
        PythonToolClient pythonToolClient = Mockito.mock(PythonToolClient.class);
        PythonToolResponseEntity pythonResponse = new PythonToolResponseEntity();
        pythonResponse.setSuccess(true);
        pythonResponse.setStatus("success");
        pythonResponse.setOutput("file content");
        pythonResponse.setLlmObservation("已读取文件内容。");
        Mockito.when(pythonToolClient.execute(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(ToolInvokeRequest.class),
                Mockito.any(AgentContext.class)
        )).thenReturn(pythonResponse);

        ToolCollection pythonTools = new ToolCollection(List.of(
                new PythonToolAdapter(
                        "file_read",
                        "read file from workspace",
                        Map.of("type", "object"),
                        "http://localhost:18081",
                        "/api/v1/tools/execute",
                        pythonToolClient
                )
        ));
        ToolExecutionResult pythonResult = toolRunner.run(
                pythonTools,
                ToolInvokeRequest.builder()
                        .toolName("file_read")
                        .toolCallId("call-py")
                        .arguments(Map.of("path", "README.md"))
                        .build(),
                AgentContext.builder().build()
        );
        Assert.assertEquals("python", pythonResult.getSourceType());
        Assert.assertTrue(pythonResult.isSuccess());

        McpToolDefinition mcpDefinition = McpToolDefinition.builder()
                .mcpId("mcp-key")
                .remoteToolName("echo")
                .exposedToolName("mcp__mcp-key__echo")
                .description("mcp")
                .transportType("stdio")
                .inputSchema(Map.of("type", "object"))
                .build();
        McpToolAccessor mcpAccessor = Mockito.mock(McpToolAccessor.class);
        Mockito.when(mcpAccessor.execute(Mockito.eq(mcpDefinition), Mockito.any(), Mockito.any()))
                .thenReturn(ToolResultPayload.success("echo", "MCP 已返回 echo。", Map.of("echo", true)));
        ToolCollection mcpTools = new ToolCollection(List.of(new McpToolAdapter(mcpDefinition, mcpAccessor)));
        ToolExecutionResult mcpResult = toolRunner.run(
                mcpTools,
                ToolInvokeRequest.builder()
                        .toolName("mcp__mcp-key__echo")
                        .toolCallId("call-mcp")
                        .build(),
                AgentContext.builder().build()
        );
        Assert.assertEquals("mcp", mcpResult.getSourceType());
        Assert.assertEquals("success", mcpResult.getStatus());
    }

    @Test
    public void shouldProjectSkillPayloadIntoExecutionResult() {
        SkillExecutor skillExecutor = Mockito.mock(SkillExecutor.class);
        Mockito.when(skillExecutor.execute(
                Mockito.anyString(),
                Mockito.anyMap(),
                Mockito.any(AgentContext.class),
                Mockito.anyString()
        )).thenReturn(SkillExecutionResult.builder()
                .success(true)
                .status("success")
                .skillName("workspace-readme-summarizer")
                .skillExecutionId("skill-exec-1")
                .finalAnswer("Skill execution finished")
                .llmObservation("Skill 已执行完成，可继续返回最终结果。")
                .skillResultSummary("Skill execution finished")
                .ledgerFacts(List.of(Map.of("factType", "skill_execution")))
                .artifactRefs(List.of("artifacts/readme-summary.txt"))
                .usedTools(List.of("file_read"))
                .build());
        SkillExecuteTool skillExecuteTool = new SkillExecuteTool(
                "skill_execute_tool",
                "skill",
                Map.of("type", "object"),
                skillExecutor
        );
        ToolExecutionResult result = toolRunner.run(
                new ToolCollection(List.of(skillExecuteTool)),
                ToolInvokeRequest.builder()
                        .toolName("skill_execute_tool")
                        .toolCallId("skill-call-1")
                        .arguments(Map.of("skill_name", "workspace-readme-summarizer"))
                        .build(),
                AgentContext.builder().build()
        );
        Assert.assertEquals("skill", result.getSourceType());
        Assert.assertEquals("workspace-readme-summarizer", result.getAttributes().get("skillName"));
        Assert.assertEquals(1, result.getArtifactRefs().size());
        Assert.assertEquals("Skill 已执行完成，可继续返回最终结果。", result.getLlmObservation());
        Assert.assertEquals(1, result.getLedgerFacts().size());
    }

    @Test
    public void shouldClassifyAllMasterStyleSkillToolsAsSkillSource() {
        List<String> skillToolNames = List.of(
                "skill_tool",
                "skill_read_tool",
                "skill_list_directory_tool",
                "skill_glob_tool",
                "skill_grep_tool",
                "skill_script_runner_tool",
                "skill_execute_tool"
        );

        for (String toolName : skillToolNames) {
            ToolExecutionResult result = toolRunner.run(
                    new ToolCollection(List.of(new NamedSuccessTool(toolName))),
                    ToolInvokeRequest.builder().toolName(toolName).toolCallId("call-" + toolName).build(),
                    AgentContext.builder().build()
            );
            Assert.assertEquals(toolName, "skill", result.getSourceType());
            Assert.assertTrue(toolName, result.isSuccess());
        }
    }

    private static class CustomJavaTool implements BaseTool {

        @Override
        public String name() {
            return "custom_java_tool";
        }

        @Override
        public String description() {
            return "custom java tool";
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
                    .toolResult("custom java tool result")
                    .llmObservation("已完成自定义 Java 工具处理，可继续基于结果推进。")
                    .structuredOutput(Map.of("done", true))
                    .ledgerFacts(List.of(Map.of("factType", "custom_java_tool_done")))
                    .build();
        }
    }

    private static class NamedSuccessTool implements BaseTool {
        private final String name;

        private NamedSuccessTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "skill runtime tool";
        }

        @Override
        public Object params() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return ToolResultPayload.success("ok", "Skill 工具执行成功。", Map.of("toolName", name));
        }
    }
}
