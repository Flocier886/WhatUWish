package com.flocier.domain.agent.runtime.agent.tool;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.impl.EchoTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillExecuteTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillGlobTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillGrepTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillListDirectoryTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillReadTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillScriptRunnerTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillViewTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillsSearchTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.WorkspaceFileTool;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.file.WorkspaceArtifactRegistrationService;
import com.flocier.domain.agent.runtime.agent.tool.impl.McpToolAdapter;
import com.flocier.domain.agent.runtime.agent.tool.mcp.McpToolDefinition;
import com.flocier.domain.agent.runtime.agent.tool.impl.ArtifactReadTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolAdapter;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolClient;
import com.flocier.domain.agent.runtime.agent.tool.impl.MaterialTool;
import com.flocier.domain.agent.runtime.agent.tool.docgen.DocGenContracts;
import com.flocier.domain.agent.runtime.agent.tool.docgen.DocGenMaterialTool;
import com.flocier.domain.agent.runtime.agent.tool.canvas.CanvasPublishTool;
import com.flocier.domain.agent.runtime.agent.tool.canvas.GetHtmlCanvasGuideTool;
import com.flocier.domain.agent.runtime.agent.tool.genui.GetGenuiGuideTool;
import com.flocier.domain.agent.runtime.agent.tool.genui.ListUiComponentsTool;
import com.flocier.domain.agent.runtime.agent.tool.genui.EmitUiTreeTool;
import com.flocier.domain.agent.runtime.agent.tool.genui.EmitUiPatchTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.CodeExecutionTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.BashTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.RequestDesktopControlTool;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopSessionPort;
import com.flocier.domain.agent.runtime.agent.tool.impl.HostCliTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.OpenCliBrowserTool;
import com.flocier.domain.agent.adapter.port.cli.CliExecutionPort;
import com.flocier.domain.agent.adapter.port.BrowserRelaySocketPort;
import com.flocier.domain.agent.adapter.port.AgentBrowserSessionPort;
import com.flocier.domain.agent.browser.LocalBrowserOwner;
import com.flocier.types.agent.config.HostCliProperties;
import com.flocier.types.agent.config.OpenCliProperties;
import com.flocier.types.agent.config.BrowserRelayProperties;
import com.flocier.domain.agent.runtime.agent.tool.rich.codeinterpreter.CodeInterpreterTool;
import com.flocier.domain.agent.runtime.agent.tool.rich.codeinterpreter.CodeInterpreterStreamClient;
import com.flocier.domain.agent.runtime.artifact.WorkspaceArtifactDeliveryService;
import com.flocier.domain.agent.runtime.file.WorkspaceOutputPersistence;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import com.alibaba.fastjson.JSON;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import com.flocier.domain.agent.runtime.agent.tool.impl.RagRetrievalTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SessionSearchTool;
import com.flocier.domain.agent.runtime.memory.SessionSearchService;
import com.flocier.domain.agent.runtime.artifact.ArtifactReadAccessResolver;
import com.flocier.domain.agent.service.armory.support.RagAdvanceAdvisorBeansProvider;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch.DeepSearchStreamClient;
import com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch.DeepSearchRuntimeFactService;
import com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch.DeepSearchTool;
import com.flocier.domain.agent.runtime.agent.tool.rich.report.ReportInputArtifactResolver;
import com.flocier.domain.agent.runtime.agent.tool.rich.report.ReportInputArtifactCandidateResolver;
import com.flocier.domain.agent.runtime.agent.tool.rich.report.ReportRuntimeFactService;
import com.flocier.domain.agent.runtime.agent.tool.rich.report.ReportStreamClient;
import com.flocier.domain.agent.runtime.agent.tool.rich.report.ReportTool;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationInputArtifactResolver;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationExecutionKernel;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationRuntimeFactService;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationTool;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.rich.RichRuntimeProgressRecordingService;
import com.flocier.domain.agent.runtime.skill.SkillExecutor;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillVirtualPaths;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DefaultToolCollectionFactory implements ToolCollectionFactory {

    @Resource
    private ReactorConfig reactorConfig;
    @Resource
    private McpToolAccessor mcpToolAccessor;
    @Resource
    private PythonToolClient pythonToolClient;
    @Resource
    private ArtifactReadAccessResolver artifactReadAccessResolver;
    @Resource
    @Qualifier("vectorStore")
    private VectorStore vectorStore;
    @Resource
    private RagAdvanceAdvisorBeansProvider ragAdvanceAdvisorBeansProvider;
    @Resource
    private DeepSearchStreamClient deepSearchStreamClient;
    @Resource
    private LedgerFactService ledgerFactService;
    @Resource
    private RichRuntimeProgressRecordingService richProgressRecordingService;
    @Resource
    private CodeInterpreterStreamClient codeInterpreterStreamClient;
    @Resource
    private WorkspaceArtifactDeliveryService workspaceArtifactDeliveryService;
    @Resource
    private DeepSearchRuntimeFactService deepSearchRuntimeFactService;
    @Resource
    private ReportStreamClient reportStreamClient;
    @Resource
    private ReportInputArtifactResolver reportInputArtifactResolver;
    @Resource
    private ReportInputArtifactCandidateResolver reportInputArtifactCandidateResolver;
    @Resource
    private ReportRuntimeFactService reportRuntimeFactService;
    @Resource
    private RuntimeEventPublisher runtimeEventPublisher;
    @Resource
    private ImageGenerationExecutionKernel imageGenerationExecutionKernel;
    @Resource
    private ImageGenerationInputArtifactResolver imageGenerationInputArtifactResolver;
    @Resource
    private ImageGenerationRuntimeFactService imageGenerationRuntimeFactService;
    @Resource
    private SkillRegistry skillRegistry;
    @Resource
    private SkillExecutor skillExecutor;
    @Resource
    private SkillPathGuard skillPathGuard;
    @Resource
    private SessionSearchService sessionSearchService;
    @Resource
    private RunWorkspaceService runWorkspaceService;
    @Resource
    private DesktopSessionPort desktopSessionPort;
    @Resource
    private WorkspaceArtifactRegistrationService workspaceArtifactRegistrationService;
    @Resource
    private CliExecutionPort cliExecutionPort;
    @Resource
    private HostCliProperties hostCliProperties;
    @Resource
    private OpenCliProperties openCliProperties;
    @Resource
    private BrowserRelayProperties browserRelayProperties;
    @Resource
    private LocalBrowserOwner localBrowserOwner;
    @Autowired
    private ObjectProvider<BrowserRelaySocketPort> browserRelaySocketPorts;
    @Autowired
    private ObjectProvider<AgentBrowserSessionPort> agentBrowserSessionPorts;
    @Value("${server.port:8100}")
    private int serverPort;
    @Resource
    private MaterialFileAccessResolver materialFileAccessResolver;
    @Resource
    private com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter materialArtifactWriter;
    @Resource
    private com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover materialArtifactRemover;
    @Value("${artifact.content.write-storage-type:local_artifact_storage}")
    private String materialArtifactStorageType;
    @Value("${artifact.content.write-prefix:}")
    private String materialArtifactPrefix;
    @Value("${conversation.material.enabled:true}")
    private boolean materialEnabled;
    @Value("${conversation.material.python-service:http://localhost:18081}")
    private String materialPythonService;
    @Value("${conversation.code-execution.enabled:false}")
    private boolean codeExecutionEnabled;
    @Value("${conversation.desktop-control.enabled:false}")
    private boolean desktopControlEnabled;
    @Value("${conversation.code-interpreter.enabled:false}")
    private boolean codeInterpreterEnabled;
    @Value("${memory.working.enabled:true}")
    private boolean workingMemoryEnabled;

    @Override
    public ToolCollection create(AgentContext context) {
        Map<String, Map<String, Object>> configuredTools = reactorConfig.getToolConfigMap();
        List<String> enabledTools = reactorConfig.getDefaultEnabledTools();
        List<BaseTool> tools = new ArrayList<>();

        for (String toolName : enabledTools) {
            Map<String, Object> config = configuredTools.get(toolName);
            if (config == null) {
                throw new IllegalStateException("Tool config not found: " + toolName);
            }
            if (!asBoolean(config.get("enabled"), true)) {
                throw new IllegalStateException("Tool is disabled: " + toolName);
            }

            String type = asString(config.get("type"));
            if (!StringUtils.hasText(type)) {
                throw new IllegalStateException("Tool type is missing: " + toolName);
            }

            // MCP 工具由数据库启用配置和 tools/list 动态发现，旧 YAML 只作为兼容占位并跳过。
            if ("mcp".equals(type)) {
                continue;
            }

            tools.add(createTool(toolName, type, config));
        }

        appendSkillToolsIfNecessary(context, tools, configuredTools);
        // 仅在本轮工作区已准备好时注册 workspace_*，避免模型获得不可执行的工具。
        // skill_* 仍只面向技能包，不与用户文件工作区混用。
        if (context != null && context.getAttribute(RunWorkspaceService.ROOT_ATTRIBUTE) != null) {
            SkillVirtualPaths skillPaths = new SkillVirtualPaths(reactorConfig.getSkillRuntimeOptions(), skillPathGuard);
            for (String workspaceTool : List.of("workspace_list", "workspace_glob", "workspace_grep",
                    "workspace_read", "workspace_write", "workspace_edit", "workspace_register")) {
                if (!hasTool(tools, workspaceTool)) {
                    tools.add(new WorkspaceFileTool(workspaceTool, runWorkspaceService,
                            workspaceArtifactRegistrationService, skillPaths, null));
                }
            }
            if (materialEnabled) {
                appendMaterialTools(tools);
                appendDocGenTools(tools);
            }
            if (!hasTool(tools, "get_html_canvas_guide")) tools.add(new GetHtmlCanvasGuideTool());
            if (!hasTool(tools, "canvas_publish"))
                tools.add(new CanvasPublishTool(runWorkspaceService, workspaceArtifactRegistrationService));
            if (!hasTool(tools, "get_genui_guide")) tools.add(new GetGenuiGuideTool());
            if (!hasTool(tools, "list_ui_components")) tools.add(new ListUiComponentsTool());
            if (!hasTool(tools, "emit_ui_tree")) tools.add(new EmitUiTreeTool());
            if (!hasTool(tools, "emit_ui_patch")) tools.add(new EmitUiPatchTool());
            if (codeExecutionEnabled && !hasTool(tools, "code_execution"))
                tools.add(new CodeExecutionTool(materialPythonService, pythonToolClient, materialFileAccessResolver,
                        materialArtifactWriter, materialArtifactRemover, materialArtifactStorageType, materialArtifactPrefix));
            if (desktopControlEnabled && codeExecutionEnabled && !hasTool(tools, RequestDesktopControlTool.NAME))
                tools.add(new RequestDesktopControlTool(desktopSessionPort));
            if (codeExecutionEnabled && !hasTool(tools, BashTool.NAME))
                tools.add(new BashTool(materialPythonService, pythonToolClient, materialFileAccessResolver,
                        materialArtifactWriter, materialArtifactRemover, materialArtifactStorageType, materialArtifactPrefix,
                        skillRegistry, reactorConfig.getSkillRuntimeOptions()));
            if (codeInterpreterEnabled && !hasTool(tools, "code_interpreter"))
                tools.add(new CodeInterpreterTool(materialPythonService, codeInterpreterStreamClient,
                        materialFileAccessResolver,
                        new WorkspaceOutputPersistence(materialArtifactWriter, materialArtifactRemover,
                                materialArtifactStorageType, materialArtifactPrefix),
                        workspaceArtifactDeliveryService, ledgerFactService, richProgressRecordingService));
            appendHostCliTool(tools, context);
            appendBrowserTools(tools);
        }
        if (workingMemoryEnabled && !hasTool(tools, SessionSearchTool.NAME)) {
            tools.add(new SessionSearchTool(sessionSearchService));
        }
        appendMcpTools(tools);

        return new ToolCollection(tools);
    }

    /** 只把当前后端实际可解析的白名单 CLI 暴露给当前会话 Agent。 */
    private void appendHostCliTool(List<BaseTool> tools, AgentContext context) {
        if (hasTool(tools, HostCliTool.TOOL_NAME) || hostCliProperties == null
                || !hostCliProperties.isEnabled() || cliExecutionPort == null) {
            return;
        }
        try {
            runWorkspaceService.root(context);
        } catch (IllegalArgumentException unavailable) {
            return;
        }
        List<String> available = new ArrayList<>();
        for (String candidate : hostCliProperties.getAllow() == null ? List.<String>of() : hostCliProperties.getAllow()) {
            if (StringUtils.hasText(candidate) && cliExecutionPort.isResolvable(candidate.trim())) {
                available.add(candidate.trim());
            }
        }
        if (!available.isEmpty()) {
            tools.add(new HostCliTool(available, cliExecutionPort, hostCliProperties, reactorConfig,
                    runWorkspaceService, workspaceArtifactRegistrationService));
        }
    }

    /**
     * 按当前运行环境装配浏览器工具：本机需扩展在线，远端需 Kernel 已配置。
     * 两者均要求 OpenCLI 可解析，避免把不可执行的能力暴露给 Agent。
     */
    private void appendBrowserTools(List<BaseTool> tools) {
        if (openCliProperties == null || !openCliProperties.isEnabled() || cliExecutionPort == null
                || !cliExecutionPort.isResolvable(openCliProperties.getCommand())) return;
        BrowserRelaySocketPort relay = browserRelaySocketPorts.getIfAvailable();
        AgentBrowserSessionPort sessions = agentBrowserSessionPorts.getIfAvailable();
        String relayUrl = "http://127.0.0.1:" + serverPort + "/internal/browser/rpc";
        if (browserRelayProperties != null && browserRelayProperties.isEnabled() && relay != null
                && relay.isOnline(localBrowserOwner.requireOwnerId()) && !hasTool(tools, "browser")) {
            tools.add(new OpenCliBrowserTool(false, cliExecutionPort, openCliProperties, browserRelayProperties,
                    relay, sessions, localBrowserOwner, runWorkspaceService, workspaceArtifactRegistrationService, relayUrl));
        }
        if (sessions != null && sessions.isConfigured() && !hasTool(tools, "agent_browser")) {
            tools.add(new OpenCliBrowserTool(true, cliExecutionPort, openCliProperties, browserRelayProperties,
                    relay, sessions, localBrowserOwner, runWorkspaceService, workspaceArtifactRegistrationService, relayUrl));
        }
    }

    /** 从材料目录读取模型可见定义，各名称复用同一执行适配器。 */
    private void appendMaterialTools(List<BaseTool> tools) {
        try (var input = new ClassPathResource("material-tool-catalog.json").getInputStream()) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> catalog = JSON.parseObject(json, Map.class);
            for (var entry : catalog.entrySet()) {
                if (hasTool(tools, entry.getKey())) continue;
                Map<?, ?> definition = (Map<?, ?>) entry.getValue();
                tools.add(new MaterialTool(entry.getKey(), String.valueOf(definition.get("description")),
                        definition.get("params"), materialPythonService, pythonToolClient, materialFileAccessResolver,
                        materialArtifactWriter, materialArtifactRemover, materialArtifactStorageType, materialArtifactPrefix));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("材料工具目录无法加载", failure);
        }
    }

    private void appendDocGenTools(List<BaseTool> tools) {
        for (var contract : DocGenContracts.all()) {
            if (hasTool(tools, contract.getName())) continue;
            tools.add(new DocGenMaterialTool(contract, materialPythonService, pythonToolClient,
                    materialFileAccessResolver, materialArtifactWriter, materialArtifactRemover,
                    materialArtifactStorageType, materialArtifactPrefix));
        }
    }

    /** 复盘单独创建工具实例，不发现 MCP、不构建业务工具、不绑定父上下文。 */
    public ToolCollection createSkillReviewTools(com.flocier.domain.agent.runtime.memory.review.SkillReviewFiles files) {
        List<BaseTool> tools = new ArrayList<>();
        SkillVirtualPaths skillPaths = new SkillVirtualPaths(reactorConfig.getSkillRuntimeOptions(), skillPathGuard);
        tools.add(new SkillViewTool(skillRegistry, skillPathGuard));
        tools.add(new SkillsSearchTool(skillRegistry));
        for (String name : List.of("workspace_list", "workspace_glob", "workspace_grep", "workspace_read",
                "workspace_write", "workspace_edit")) {
            tools.add(new WorkspaceFileTool(name, runWorkspaceService, null, skillPaths, files));
        }
        return new ToolCollection(tools);
    }

    /** 根据配置中的工具类型创建对应的运行时工具实例。 */
    private BaseTool createTool(String toolName, String type, Map<String, Object> config) {
        return switch (type) {
            case "java" -> new EchoTool(
                    toolName,
                    description(toolName, config),
                    params(config)
            );
            case "python" -> new PythonToolAdapter(
                    toolName,
                    description(toolName, config),
                    params(config),
                    asString(config.get("pythonService")),
                    asString(config.get("pythonEndpoint")),
                    pythonToolClient
            );
            case "artifact_read" -> new ArtifactReadTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    asString(config.get("pythonService")),
                    asString(config.get("pythonEndpoint")),
                    pythonToolClient,
                    artifactReadAccessResolver
            );
            case "rag_retrieval" -> createRagRetrievalTool(toolName, config);
            case "rich_python" -> new DeepSearchTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    asString(config.get("pythonService")),
                    asString(config.get("pythonEndpoint")),
                    deepSearchStreamClient,
                    ledgerFactService,
                    deepSearchRuntimeFactService
            );
            case "rich_report" -> new ReportTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    asString(config.get("pythonService")),
                    asString(config.get("pythonEndpoint")),
                    reportStreamClient,
                    reportInputArtifactCandidateResolver,
                    reportInputArtifactResolver,
                    ledgerFactService,
                    reportRuntimeFactService,
                    runtimeEventPublisher
            );
            case "rich_image_generation" -> new ImageGenerationTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    asString(config.get("pythonService")),
                    asString(config.get("pythonEndpoint")),
                    imageGenerationExecutionKernel,
                    imageGenerationInputArtifactResolver,
                    ledgerFactService,
                    imageGenerationRuntimeFactService
            );
            case "skill" -> new SkillTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    skillRegistry,
                    reactorConfig.getSkillRuntimeOptions()
            );
            case "skill_view" -> new SkillViewTool(skillRegistry, skillPathGuard);
            case "skills_search" -> new SkillsSearchTool(skillRegistry);
            case "skill_execute" -> new SkillExecuteTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    skillExecutor
            );
            case "skill_read" -> new SkillReadTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    skillRegistry,
                    reactorConfig.getSkillRuntimeOptions(),
                    skillPathGuard
            );
            case "skill_list_directory" -> new SkillListDirectoryTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    skillRegistry,
                    reactorConfig.getSkillRuntimeOptions(),
                    skillPathGuard
            );
            case "skill_glob" -> new SkillGlobTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    skillRegistry,
                    reactorConfig.getSkillRuntimeOptions(),
                    skillPathGuard
            );
            case "skill_grep" -> new SkillGrepTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    skillRegistry,
                    reactorConfig.getSkillRuntimeOptions(),
                    skillPathGuard
            );
            case "skill_script_runner" -> new SkillScriptRunnerTool(
                    toolName,
                    description(toolName, config),
                    params(config),
                    skillRegistry,
                    reactorConfig.getSkillRuntimeOptions(),
                    firstNonBlank(asString(config.get("pythonService")), defaultPythonService()),
                    firstNonBlank(asString(config.get("pythonEndpoint")), "/api/v1/tools/execute"),
                    pythonToolClient
            );
            default -> throw new IllegalStateException("Unsupported tool type: " + type + ", tool=" + toolName);
        };
    }

    /**
     * RAG 的查询改写、向量、BM25、RRF 与精排配置只由服务端掌控，
     * 模型调用时仅能选择 query、knowledgeTag 与最终 topK。
     */
    private BaseTool createRagRetrievalTool(String toolName, Map<String, Object> config) {
        int vectorTopK = boundedPositiveInteger(config.get("vectorTopK"), 12, 1, 48);
        int keywordTopK = boundedPositiveInteger(config.get("keywordTopK"), 12, 0, 48);
        int rerankTopK = boundedPositiveInteger(config.get("rerankTopK"), 5, 1, 12);
        EnhancedRagRetriever.QueryRewriter queryRewriter = enabled(config.get("queryRewrite"), true)
                ? ragAdvanceAdvisorBeansProvider.getQueryRewriter("OpenAiQueryRewriter") : null;
        EnhancedRagRetriever.KeywordRetriever keywordRetriever = keywordTopK > 0
                ? ragAdvanceAdvisorBeansProvider.getKeywordRetriever("EsBm25KeywordRetriever") : null;
        EnhancedRagRetriever.Reranker reranker = enabled(config.get("rerank"), true)
                ? ragAdvanceAdvisorBeansProvider.getReranker("BgeRerankerV2M3Client") : null;
        EnhancedRagRetriever retriever = new EnhancedRagRetriever(
                vectorStore,
                SearchRequest.builder().topK(vectorTopK).build(),
                keywordRetriever,
                keywordTopK,
                queryRewriter,
                reranker,
                rerankTopK
        );
        return new RagRetrievalTool(toolName, description(toolName, config), params(config), retriever, rerankTopK);
    }

    private void appendMcpTools(List<BaseTool> tools) {
        for (McpToolDefinition definition : mcpToolAccessor.listTools()) {
            if (definition == null || !StringUtils.hasText(definition.getExposedToolName())) {
                continue;
            }
            boolean duplicate = tools.stream().anyMatch(tool -> definition.getExposedToolName().equals(tool.name()));
            if (duplicate) {
                throw new IllegalStateException("Dynamic MCP tool conflicts with existing tool: "
                        + definition.getExposedToolName());
            }
            tools.add(new McpToolAdapter(definition, mcpToolAccessor));
        }
    }

    /**
     * 当 skill runtime 开启并允许当前链路使用时，自动补充 skill 相关高阶工具。
     */
    private void appendSkillToolsIfNecessary(AgentContext context,
                                             List<BaseTool> tools,
                                             Map<String, Map<String, Object>> configuredTools) {
        if (reactorConfig.getSkillRuntimeOptions() == null
                || !reactorConfig.getSkillRuntimeOptions().isEnabled()
                || !reactorConfig.getSkillRuntimeOptions().isReactEnabled()
                || !reactorConfig.getSkillRuntimeOptions().isMasterStyleToolsEnabled()) {
            return;
        }
        addIfMissing(tools, "skills_search", "skills_search", Map.of());
        addIfMissing(tools, "skill_view", "skill_view", Map.of());
        if (reactorConfig.getSkillRuntimeOptions().isLegacySkillExecuteToolEnabled()) {
            addIfMissing(tools, "skill_execute_tool", "skill_execute", configuredTools.getOrDefault("skill_execute_tool", defaultSkillExecuteToolConfig()));
        }
    }

    private void addIfMissing(List<BaseTool> tools, String toolName, String type, Map<String, Object> config) {
        if (!hasTool(tools, toolName)) {
            tools.add(createTool(toolName, type, config));
        }
    }

    private boolean hasTool(List<BaseTool> tools, String toolName) {
        return tools.stream().anyMatch(item -> toolName.equals(item.name()));
    }

    private Map<String, Object> defaultSkillToolConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("description", "查看已注册的 skill 能力信息，或读取某个 skill 的说明与限制");
        config.put("params", Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill_name", Map.of("type", "string", "description", "要查看的 skill 名称，不传则列出全部"),
                        "include_content", Map.of("type", "boolean", "description", "是否返回 SKILL.md 与 prompt 内容")
                )
        ));
        return config;
    }

    private Map<String, Object> defaultSkillReadToolConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("description", "读取指定 skill 包内的文本文件，路径只能位于该 skill 包内。");
        config.put("params", Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill_name", Map.of("type", "string", "description", "skill 名称"),
                        "path", Map.of("type", "string", "description", "相对 skill 包根目录的文件路径"),
                        "start_line", Map.of("type", "integer", "description", "起始行，默认 1"),
                        "line_count", Map.of("type", "integer", "description", "读取行数，默认 120")
                ),
                "required", List.of("skill_name", "path")
        ));
        return config;
    }

    private Map<String, Object> defaultSkillListDirectoryToolConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("description", "列出指定 skill 包内目录，路径只能位于该 skill 包内。");
        config.put("params", Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill_name", Map.of("type", "string", "description", "skill 名称"),
                        "path", Map.of("type", "string", "description", "相对 skill 包根目录的目录路径"),
                        "max_depth", Map.of("type", "integer", "description", "最大目录深度，默认 2")
                ),
                "required", List.of("skill_name")
        ));
        return config;
    }

    private Map<String, Object> defaultSkillGlobToolConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("description", "在指定 skill 包内按 glob 模式查找文件。");
        config.put("params", Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill_name", Map.of("type", "string", "description", "skill 名称"),
                        "path", Map.of("type", "string", "description", "搜索起点，相对 skill 包根目录"),
                        "pattern", Map.of("type", "string", "description", "glob 模式，例如 **/*.md")
                ),
                "required", List.of("skill_name", "pattern")
        ));
        return config;
    }

    private Map<String, Object> defaultSkillGrepToolConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("description", "在指定 skill 包内搜索文本内容。");
        config.put("params", Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill_name", Map.of("type", "string", "description", "skill 名称"),
                        "path", Map.of("type", "string", "description", "文件或目录路径，相对 skill 包根目录"),
                        "pattern", Map.of("type", "string", "description", "搜索关键词或正则"),
                        "regex", Map.of("type", "boolean", "description", "是否按正则搜索"),
                        "case_sensitive", Map.of("type", "boolean", "description", "是否大小写敏感")
                ),
                "required", List.of("skill_name", "pattern")
        ));
        return config;
    }

    private Map<String, Object> defaultSkillScriptRunnerToolConfig(Map<String, Map<String, Object>> configuredTools) {
        Map<String, Object> baseRunner = configuredTools.getOrDefault("skill_script_runner", Map.of());
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("description", "执行指定 skill 已注册的脚本。只能传 skill_name 和 script_name，不能传任意脚本路径。");
        config.put("pythonService", firstNonBlank(asString(baseRunner.get("pythonService")), defaultPythonService()));
        config.put("pythonEndpoint", firstNonBlank(asString(baseRunner.get("pythonEndpoint")), "/api/v1/tools/execute"));
        config.put("params", Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill_name", Map.of("type", "string", "description", "skill 名称"),
                        "script_name", Map.of("type", "string", "description", "已注册脚本名称"),
                        "arguments", Map.of("type", "object", "description", "传给脚本的结构化参数"),
                        "argv", Map.of("type", "array", "description", "可选命令行参数"),
                        "timeout_seconds", Map.of("type", "integer", "description", "本次执行超时秒数")
                ),
                "required", List.of("skill_name", "script_name")
        ));
        return config;
    }

    private Map<String, Object> defaultSkillExecuteToolConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("description", "兼容或高级封装入口：在独立 skill 子链路中执行已注册 skill。普通 skill 使用优先选择 skill_tool、skill_read_tool、skill_grep_tool 和 skill_script_runner_tool。");
        config.put("params", Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill_name", Map.of("type", "string", "description", "要执行的 skill 名称"),
                        "task", Map.of("type", "string", "description", "本次 skill 的任务描述"),
                        "arguments", Map.of("type", "object", "description", "传给该 skill 的结构化参数")
                ),
                "required", List.of("skill_name")
        ));
        return config;
    }

    /**
     * 提取工具参数 schema，供 LLM 侧构建函数调用描述。
     */
    private Object params(Map<String, Object> config) {
        Object value = config.get("params");
        return value == null ? Map.of() : value;
    }

    private String description(String toolName, Map<String, Object> config) {
        String description = asString(config.get("description"));
        return StringUtils.hasText(description) ? description : toolName;
    }

    private String asString(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String defaultPythonService() {
        return "http://localhost:18081";
    }

    private boolean asBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private boolean enabled(Object raw, boolean defaultValue) {
        return asBoolean(raw, defaultValue);
    }

    private int boundedPositiveInteger(Object raw, int defaultValue, int minimum, int maximum) {
        int value = defaultValue;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw != null && StringUtils.hasText(String.valueOf(raw))) {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid integer tool configuration: " + raw, exception);
            }
        }
        if (value < minimum || value > maximum) {
            throw new IllegalStateException("Tool configuration value must be within " + minimum + " and " + maximum + ": " + value);
        }
        return value;
    }
}
