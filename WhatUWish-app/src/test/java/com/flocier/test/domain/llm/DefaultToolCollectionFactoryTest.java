package com.flocier.test.domain.llm;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.DefaultToolCollectionFactory;
import com.flocier.domain.agent.runtime.agent.tool.DefaultToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.McpToolAccessor;
import com.flocier.domain.agent.runtime.agent.tool.mcp.McpToolDefinition;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolClient;
import com.flocier.domain.agent.runtime.agent.tool.impl.RagRetrievalTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SessionSearchTool;
import com.flocier.domain.agent.adapter.port.cli.CliExecutionPort;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.memory.SessionSearchService;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.service.armory.support.RagAdvanceAdvisorBeansProvider;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import com.flocier.domain.agent.runtime.skill.DefaultSkillParser;
import com.flocier.domain.agent.runtime.skill.DefaultSkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillExecutor;
import com.flocier.domain.agent.runtime.skill.SkillMarkdownParser;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillYamlParser;
import com.flocier.types.agent.config.HostCliProperties;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultToolCollectionFactoryTest {

    @Test
    public void shouldExposeOnlyResolvableHostCliForPreparedWorkspace() {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "toolConfigMap", Map.of());
        ReflectionTestUtils.setField(config, "toolDefaults", Map.of("enabledTools", List.of()));
        ReflectionTestUtils.setField(config, "skillRuntimeOptions", SkillRuntimeOptions.builder()
                .enabled(false).reactEnabled(true).masterStyleToolsEnabled(true).build());
        McpToolAccessor accessor = Mockito.mock(McpToolAccessor.class);
        Mockito.when(accessor.listTools()).thenReturn(List.of());
        CliExecutionPort cli = Mockito.mock(CliExecutionPort.class);
        Mockito.when(cli.isResolvable("twitter")).thenReturn(true);
        Mockito.when(cli.isResolvable("rdt")).thenReturn(false);
        RunWorkspaceService workspace = Mockito.mock(RunWorkspaceService.class);
        HostCliProperties properties = new HostCliProperties();
        properties.setAllow(List.of("twitter", "rdt"));

        DefaultToolCollectionFactory factory = new DefaultToolCollectionFactory();
        ReflectionTestUtils.setField(factory, "reactorConfig", config);
        ReflectionTestUtils.setField(factory, "mcpToolAccessor", accessor);
        ReflectionTestUtils.setField(factory, "cliExecutionPort", cli);
        ReflectionTestUtils.setField(factory, "hostCliProperties", properties);
        ReflectionTestUtils.setField(factory, "runWorkspaceService", workspace);
        ReflectionTestUtils.setField(factory, "materialEnabled", false);
        ReflectionTestUtils.setField(factory, "workingMemoryEnabled", false);

        AgentContext context = AgentContext.builder().sessionId("session").build();
        assertFalse(factory.create(context).getOptional("skills_search").isPresent());
        assertFalse(factory.create(context).getOptional("skill_view").isPresent());
        assertFalse(factory.create(context).getOptional("host_cli").isPresent());
        context.setAttribute(RunWorkspaceService.ROOT_ATTRIBUTE, Path.of("run-workspace"));
        Mockito.when(workspace.root(context)).thenReturn(Path.of("run-workspace"));
        ToolCollection tools = factory.create(context);
        assertTrue(tools.getOptional("host_cli").isPresent());
        Map<?, ?> params = (Map<?, ?>) tools.getRequired("host_cli").params();
        Map<?, ?> fields = (Map<?, ?>) params.get("properties");
        assertEquals(List.of("twitter"), ((Map<?, ?>) fields.get("tool")).get("enum"));

        properties.setEnabled(false);
        assertFalse(factory.create(context).getOptional("host_cli").isPresent());
    }

    @Test
    public void shouldExposeMaterialToolsOnlyForEnabledRunWorkspace() {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "toolConfigMap", Map.of());
        ReflectionTestUtils.setField(config, "toolDefaults", Map.of("enabledTools", List.of()));
        ReflectionTestUtils.setField(config, "skillRuntimeOptions", SkillRuntimeOptions.builder()
                .enabled(false).reactEnabled(true).masterStyleToolsEnabled(true).build());
        McpToolAccessor accessor = Mockito.mock(McpToolAccessor.class);
        Mockito.when(accessor.listTools()).thenReturn(List.of());
        DefaultToolCollectionFactory factory = new DefaultToolCollectionFactory();
        ReflectionTestUtils.setField(factory, "reactorConfig", config);
        ReflectionTestUtils.setField(factory, "mcpToolAccessor", accessor);
        ReflectionTestUtils.setField(factory, "workingMemoryEnabled", false);
        ReflectionTestUtils.setField(factory, "materialEnabled", true);

        AgentContext context = AgentContext.builder().build();
        assertFalse(factory.create(context).getOptional("csv_processor").isPresent());

        context.setAttribute(RunWorkspaceService.ROOT_ATTRIBUTE, Path.of("run-workspace"));
        ToolCollection materials = factory.create(context);
        assertTrue(materials.getOptional("csv_processor").isPresent());
        List<String> names = List.of("pdf_reader", "pdf_structure", "citation_extractor", "word_reader",
                "excel_reader", "csv_processor", "html_processor", "markdown_processor", "text_processor",
                "image_ocr", "data_clean", "data_merge", "data_transform", "data_aggregate",
                "data_validate", "sql_query");
        assertEquals(16, names.stream().filter(name -> materials.getOptional(name).isPresent()).count());
        List<String> docgen = List.of("document_generate", "slides_generate", "excel_generator",
                "chart_generator", "checklist_generate", "template_filler", "document_template", "theme_designer");
        assertEquals(8, docgen.stream().filter(name -> materials.getOptional(name).isPresent()).count());
        assertTrue(materials.getRequired("document_generate").description().contains("PDF, DOCX, HTML"));
        assertEquals(1, names.stream().map(name -> materials.getRequired(name).getClass()).distinct().count());
        assertTrue(materials.getRequired("pdf_reader").description().contains("use pdf_structure"));
        assertTrue(materials.getRequired("data_clean").description().contains("fill_value (not value)"));
        assertTrue(materials.getRequired("sql_query").description().contains("Read-only SQL SELECT"));
        Map<?, ?> pdfSchema = (Map<?, ?>) materials.getRequired("pdf_reader").params();
        assertTrue(((List<?>) pdfSchema.get("required")).isEmpty());
        Map<?, ?> pdfProperties = (Map<?, ?>) pdfSchema.get("properties");
        assertTrue(pdfProperties.containsKey("merge_files"));
        assertTrue(String.valueOf(((Map<?, ?>) pdfProperties.get("file_path")).get("description"))
                .contains("会话工作区"));
        String definitions = com.alibaba.fastjson.JSON.toJSONString(materials.exportToolDefinitions());
        assertFalse(definitions.contains("本轮相对路径"));
        assertFalse(definitions.contains("minio://"));

        ReflectionTestUtils.setField(factory, "codeExecutionEnabled", true);
        ToolCollection withCode = factory.create(context);
        assertTrue(withCode.getOptional("code_execution").isPresent());
        assertTrue(withCode.exportToolDefinitions().containsKey("code_execution"));
        assertTrue(withCode.getOptional("bash").isPresent());
        ReflectionTestUtils.setField(factory, "desktopControlEnabled", true);
        ToolCollection withDesktop = factory.create(context);
        assertTrue(withDesktop.getOptional("bash").isPresent());
        assertTrue(withDesktop.getOptional("RequestDesktopControl").isPresent());
        assertTrue(withDesktop.exportToolDefinitions().containsKey("bash"));
        ReflectionTestUtils.setField(factory, "codeExecutionEnabled", false);
        assertFalse(factory.create(context).getOptional("bash").isPresent());

        ReflectionTestUtils.setField(factory, "materialEnabled", false);
        assertFalse(factory.create(context).getOptional("csv_processor").isPresent());
    }

    @Test
    public void shouldRegisterSessionSearchOnlyWhenWorkingMemoryEnabled() {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "toolConfigMap", Map.of());
        ReflectionTestUtils.setField(config, "toolDefaults", Map.of("enabledTools", List.of()));
        ReflectionTestUtils.setField(config, "skillRuntimeOptions", SkillRuntimeOptions.builder()
                .enabled(false).reactEnabled(true).masterStyleToolsEnabled(true).build());
        McpToolAccessor accessor = Mockito.mock(McpToolAccessor.class);
        Mockito.when(accessor.listTools()).thenReturn(List.of());
        DefaultToolCollectionFactory factory = new DefaultToolCollectionFactory();
        ReflectionTestUtils.setField(factory, "reactorConfig", config);
        ReflectionTestUtils.setField(factory, "mcpToolAccessor", accessor);
        ReflectionTestUtils.setField(factory, "sessionSearchService", Mockito.mock(SessionSearchService.class));
        ReflectionTestUtils.setField(factory, "workingMemoryEnabled", true);
        ToolCollection enabled = factory.create(AgentContext.builder().build());
        assertTrue(enabled.getRequired(SessionSearchTool.NAME) instanceof SessionSearchTool);
        assertTrue(enabled.exportToolDefinitions().containsKey(SessionSearchTool.NAME));
        ReflectionTestUtils.setField(factory, "workingMemoryEnabled", false);
        assertFalse(factory.create(AgentContext.builder().build()).getOptional(SessionSearchTool.NAME).isPresent());
    }

    @Test
    public void shouldCreateConfiguredEnhancedRagRetrievalTool() {
        ReactorConfig reactorConfig = new ReactorConfig();
        ReflectionTestUtils.setField(reactorConfig, "toolConfigMap", Map.of(
                "rag_retrieval_tool", Map.of(
                        "type", "rag_retrieval",
                        "enabled", true,
                        "description", "查询内部知识库",
                        "params", Map.of("type", "object"),
                        "vectorTopK", 12,
                        "keywordTopK", 12,
                        "rerankTopK", 5,
                        "queryRewrite", true,
                        "rerank", true
                )
        ));
        ReflectionTestUtils.setField(reactorConfig, "toolDefaults", Map.of(
                "enabledTools", List.of("rag_retrieval_tool")
        ));
        ReflectionTestUtils.setField(reactorConfig, "skillRuntimeOptions", SkillRuntimeOptions.builder()
                .enabled(false).reactEnabled(true).masterStyleToolsEnabled(true).build());

        McpToolAccessor mcpToolAccessor = Mockito.mock(McpToolAccessor.class);
        Mockito.when(mcpToolAccessor.listTools()).thenReturn(List.of());
        RagAdvanceAdvisorBeansProvider provider = Mockito.mock(RagAdvanceAdvisorBeansProvider.class);
        Mockito.when(provider.getQueryRewriter("OpenAiQueryRewriter"))
                .thenReturn((query, context) -> query);
        Mockito.when(provider.getKeywordRetriever("EsBm25KeywordRetriever"))
                .thenReturn((query, topK, context) -> List.of());
        Mockito.when(provider.getReranker("BgeRerankerV2M3Client"))
                .thenReturn((query, candidates, context) -> List.of());

        DefaultToolCollectionFactory factory = new DefaultToolCollectionFactory();
        ReflectionTestUtils.setField(factory, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(factory, "mcpToolAccessor", mcpToolAccessor);
        ReflectionTestUtils.setField(factory, "vectorStore", Mockito.mock(VectorStore.class));
        ReflectionTestUtils.setField(factory, "ragAdvanceAdvisorBeansProvider", provider);

        ToolCollection collection = factory.create(AgentContext.builder().build());

        assertTrue(collection.getRequired("rag_retrieval_tool") instanceof RagRetrievalTool);
        assertTrue(collection.exportToolDefinitions().containsKey("rag_retrieval_tool"));
        Mockito.verify(provider).getQueryRewriter("OpenAiQueryRewriter");
        Mockito.verify(provider).getKeywordRetriever("EsBm25KeywordRetriever");
        Mockito.verify(provider).getReranker("BgeRerankerV2M3Client");
    }

    @Test
    public void shouldReplaceStaticMcpPlaceholderWithDiscoveredTool() {
        ReactorConfig reactorConfig = new ReactorConfig();
        ReflectionTestUtils.setField(reactorConfig, "toolConfigMap", Map.of(
                "old_mcp_placeholder", Map.of("type", "mcp", "enabled", true)
        ));
        ReflectionTestUtils.setField(reactorConfig, "toolDefaults", Map.of(
                "enabledTools", List.of("old_mcp_placeholder")
        ));
        ReflectionTestUtils.setField(reactorConfig, "skillRuntimeOptions", SkillRuntimeOptions.builder()
                .enabled(false).reactEnabled(true).masterStyleToolsEnabled(true).build());

        McpToolDefinition definition = McpToolDefinition.builder()
                .mcpId("server-1")
                .remoteToolName("search")
                .exposedToolName("mcp__server-1__search")
                .description("search through MCP")
                .transportType("stdio")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string")
                )))
                .build();
        McpToolAccessor accessor = Mockito.mock(McpToolAccessor.class);
        Mockito.when(accessor.listTools()).thenReturn(List.of(definition));

        DefaultToolCollectionFactory factory = new DefaultToolCollectionFactory();
        ReflectionTestUtils.setField(factory, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(factory, "mcpToolAccessor", accessor);

        ToolCollection collection = factory.create(AgentContext.builder().build());

        assertEquals(1, collection.listTools().size());
        assertTrue(collection.getOptional("mcp__server-1__search").isPresent());
        assertFalse(collection.getOptional("old_mcp_placeholder").isPresent());
        assertTrue(collection.exportToolDefinitions().containsKey("mcp__server-1__search"));
    }

    @Test
    public void shouldCreateRealPythonToolCollectionFromReactorConfig() {
        ReactorConfig reactorConfig = new ReactorConfig();

        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();
        tools.put("web_fetch", mapOf(
                "name", "web_fetch",
                "type", "python",
                "description", "抓取网页正文",
                "enabled", true,
                "pythonService", "http://localhost:18081",
                "pythonEndpoint", "/api/v1/tools/execute",
                "params", Map.of("type", "object")
        ));
        tools.put("file_read", mapOf(
                "name", "file_read",
                "type", "python",
                "description", "读取本地文件",
                "enabled", true,
                "pythonService", "http://localhost:18081",
                "pythonEndpoint", "/api/v1/tools/execute",
                "params", Map.of("type", "object")
        ));
        ReflectionTestUtils.setField(reactorConfig, "toolConfigMap", tools);
        ReflectionTestUtils.setField(reactorConfig, "toolDefaults", Map.of("enabledTools", List.of("web_fetch", "file_read")));
        ReflectionTestUtils.setField(reactorConfig, "skillRuntimeOptions", SkillRuntimeOptions.builder()
                .enabled(true)
                .directories(List.of(resolveSkillRoot().toString()))
                .reactEnabled(true)
                .workflowEnabled(false)
                .maxReadChars(16000)
                .maxListEntries(20)
                .maxSearchMatches(20)
                .maxExecutionSteps(3)
                .defaultScriptTimeoutSeconds(30)
                .maxScriptTimeoutSeconds(120)
                .allowedScriptRuntimes(List.of("python"))
                .masterStyleToolsEnabled(true)
                .build());

        DefaultToolCollectionFactory factory = new DefaultToolCollectionFactory();
        ReflectionTestUtils.setField(factory, "reactorConfig", reactorConfig);
        McpToolAccessor mcpToolAccessor = Mockito.mock(McpToolAccessor.class);
        Mockito.when(mcpToolAccessor.listTools()).thenReturn(List.of());
        ReflectionTestUtils.setField(factory, "mcpToolAccessor", mcpToolAccessor);
        ReflectionTestUtils.setField(factory, "pythonToolClient", new PythonToolClient());
        DefaultSkillRegistry skillRegistry = new DefaultSkillRegistry(
                reactorConfig.getSkillRuntimeOptions(),
                new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()),
                new SkillPathGuard()
        );
        skillRegistry.refresh();
        ReflectionTestUtils.setField(factory, "skillRegistry", skillRegistry);
        ReflectionTestUtils.setField(factory, "skillExecutor", new SkillExecutor(
                skillRegistry,
                reactorConfig.getSkillRuntimeOptions(),
                Mockito.mock(LLM.class),
                new DefaultToolRunner()
        ));
        ReflectionTestUtils.setField(factory, "skillPathGuard", new SkillPathGuard());

        ToolCollection toolCollection = factory.create(AgentContext.builder().build());

        assertEquals(4, toolCollection.listTools().size());
        assertTrue(toolCollection.describeTools().contains("web_fetch"));
        assertTrue(toolCollection.describeTools().contains("file_read"));
        assertTrue(toolCollection.describeTools().contains("skills_search"));
        assertTrue(toolCollection.describeTools().contains("skill_view"));
        for (String legacy : List.of("skill_tool", "skill_read_tool", "skill_list_directory_tool",
                "skill_glob_tool", "skill_grep_tool", "skill_script_runner_tool"))
            assertFalse(toolCollection.exportToolDefinitions().containsKey(legacy));
        assertFalse(toolCollection.describeTools().contains("skill_execute_tool"));

        String index = ((com.flocier.domain.agent.runtime.agent.tool.impl.SkillViewTool)
                toolCollection.getRequired("skill_view")).promptIndex();
        assertTrue(index.contains("github-deep-research"));
        assertTrue(index.contains("分析仓库架构"));
        assertTrue(index.contains("skills_search"));
        assertFalse(toolCollection.getRequired("skill_view").description().contains("github-deep-research"));
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private static Path resolveSkillRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        if (current.getFileName() != null && "WhatUWish-app".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        return current.resolve("runtime").resolve("skills").normalize();
    }
}
