package com.flocier.test.domain.memory;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.RuntimeDependencies;
import com.flocier.domain.agent.runtime.agent.*;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.tool.*;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillWriteTool;
import com.flocier.domain.agent.runtime.agent.tool.rich.*;
import com.flocier.domain.agent.runtime.artifact.*;
import com.flocier.domain.agent.runtime.event.*;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.*;
import com.flocier.domain.agent.runtime.llm.toolask.*;
import com.flocier.domain.agent.runtime.memory.*;
import com.flocier.domain.agent.runtime.memory.review.*;
import com.flocier.domain.agent.runtime.printer.Printer;
import com.flocier.domain.agent.runtime.skill.*;
import com.flocier.domain.agent.runtime.stream.AgentResultWriter;
import com.flocier.domain.ledger.service.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 真实 ReAct/ToolRunner 与临时文件，隔离外部模型、数据库和用户输出。 */
public class SkillReviewIsolationTest {
    /** 数据库重载会共享不可变空集合，快照必须仍能按值复制并构造下一轮 fork。 */
    @Test public void snapshotsHydratedMessagesWithSharedEmptyToolLists() {
        var projector = new com.flocier.domain.agent.runtime.memory.WorkingMemoryProjector();
        List<LlmMessage> hydrated = projector.hydrate(projector.project(List.of(
                LlmMessage.builder().role(LlmMessageRole.USER).content("上一轮任务").build(),
                LlmMessage.builder().role(LlmMessageRole.ASSISTANT).content("上一轮完成").build()), "session", "request", "run"));
        assertSame(hydrated.get(0).getToolCalls(), hydrated.get(1).getToolCalls());
        DefaultToolCollectionFactory factory = mock(DefaultToolCollectionFactory.class);
        when(factory.createSkillReviewTools(any())).thenReturn(new ToolCollection(List.of()));
        SkillReviewRunner runner = new SkillReviewRunner(mock(ReActAgent.class), factory, mock(SkillReviewFiles.class));
        AgentContext parent = AgentContext.builder().sessionId("session").requestId("request")
                .reactMemorySnapshot(hydrated).build();
        parent.setAttribute(ReActAgent.REACT_SYSTEM_PROMPT_KEY, "主 SYSTEM");
        SkillReviewRunner.Snapshot snapshot = runner.snapshot(parent);
        SkillReviewExecution row = new SkillReviewExecution();
        row.setReviewId("review"); row.setForkRequestId("fork"); row.setMaxSteps(6);
        AgentContext fork = runner.createContext(snapshot, row, new SkillReviewState());
        assertEquals(3, fork.getMemory().size());
        assertEquals("上一轮完成", fork.getMemory().get(1).getContent());
        assertEquals("request:1", fork.getMemory().get(1).getMetadata().get("originMessageKey"));
        hydrated.get(1).getMetadata().put("originMessageKey", "changed");
        assertEquals("request:1", fork.getMemory().get(1).getMetadata().get("originMessageKey"));
    }

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void forkKeepsFrozenPrefixRunsOrderedToolsAndSuppressesMainSideEffects() throws Exception {
        Path root = temporary.newFolder("skills").toPath();
        SkillRuntimeOptions skills = SkillRuntimeOptions.builder().enabled(true).reactEnabled(true).masterStyleToolsEnabled(true)
                .directories(List.of(root.toString())).maxReadChars(16000).allowFullContentRead(true).build();
        SkillPathGuard guard = new SkillPathGuard();
        DefaultSkillRegistry registry = new DefaultSkillRegistry(skills, new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()), guard);
        registry.refresh();
        SkillReviewFiles files = new SkillReviewFiles(registry, skills, guard);
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "reactModelName", "offline-test-model");
        ReflectionTestUtils.setField(config, "skillRuntimeOptions", skills);
        DefaultToolCollectionFactory factory = new DefaultToolCollectionFactory();
        ReflectionTestUtils.setField(factory, "reactorConfig", config);
        ReflectionTestUtils.setField(factory, "skillRegistry", registry);
        ReflectionTestUtils.setField(factory, "skillPathGuard", guard);
        McpToolAccessor mcp = mock(McpToolAccessor.class);
        ReflectionTestUtils.setField(factory, "mcpToolAccessor", mcp);

        LedgerRecordingService recording = mock(LedgerRecordingService.class);
        RuntimeEventPublisher publisher = mock(RuntimeEventPublisher.class);
        LedgerFactService ledger = new LedgerFactService(recording, mock(LedgerQueryService.class), new RuntimeEventMapper(), publisher);
        DefaultToolRunner defaultRunner = new DefaultToolRunner();
        ReflectionTestUtils.setField(defaultRunner, "ledgerFactService", ledger);
        ArtifactRuntimeCollector collector = mock(ArtifactRuntimeCollector.class);
        ArtifactRuntimeLedgerRecorder artifacts = mock(ArtifactRuntimeLedgerRecorder.class);
        ReflectionTestUtils.setField(defaultRunner, "artifactRuntimeCollector", collector);
        ReflectionTestUtils.setField(defaultRunner, "artifactRuntimeLedgerRecorder", artifacts);
        RichToolRunner toolRunner = new RichToolRunner(defaultRunner);
        ReActAgent agent = new ReActAgent();
        RuntimeDependencies dependencies = mock(RuntimeDependencies.class); when(dependencies.reactorConfig()).thenReturn(config);
        ReflectionTestUtils.setField(agent, "runtimeDependencies", dependencies);
        ReflectionTestUtils.setField(agent, "toolRunner", toolRunner);
        ReflectionTestUtils.setField(agent, "workingMemoryEnabled", true);
        SessionWorkingMemoryRepository memory = mock(SessionWorkingMemoryRepository.class);
        SessionContextCompactionService compaction = mock(SessionContextCompactionService.class);
        ReflectionTestUtils.setField(agent, "workingMemoryRepository", memory);
        ReflectionTestUtils.setField(agent, "contextCompactionService", compaction);
        LLM llm = mock(LLM.class); ReflectionTestUtils.setField(agent, "llm", llm);
        List<ToolAskRequest> requests = new ArrayList<>();
        String content = "---\nname: isolated-skill\ndescription: 离线复盘流程\n---\n原流程\n";
        when(llm.toolAsk(any())).thenAnswer(call -> {
            ToolAskRequest request = call.getArgument(0); requests.add(request);
            ToolAskResponse response = requests.size() == 1 ? ToolAskResponse.builder().thought("整理流程")
                    .toolCalls(List.of(toolCall("write", "workspace_write", Map.of("path", "skills/isolated-skill/SKILL.md", "content", content)),
                            toolCall("read", "workspace_read", Map.of("path", "skills/isolated-skill/SKILL.md")),
                            toolCall("edit", "workspace_edit", Map.of("path", "skills/isolated-skill/SKILL.md", "old_string", "原流程", "new_string", "改进流程"))))
                    .build() : ToolAskResponse.builder().completed(true).thought("已保存 Skill").build();
            ledger.recordToolAsk(request, response); return response;
        });
        SkillReviewRunner runner = new SkillReviewRunner(agent, factory, files);
        Map<String, Object> mutableSchema = new HashMap<>(Map.of("type", "object", "properties", new HashMap<>()));
        BaseTool parentTool = mock(BaseTool.class); when(parentTool.name()).thenReturn("parent-business"); when(parentTool.params()).thenReturn(mutableSchema);
        LlmMessage parentMessage = LlmMessage.builder().role(LlmMessageRole.ASSISTANT).content("主任务终答").build();
        AgentContext parent = AgentContext.builder().sessionId("session").requestId("main-request")
                .toolCollection(new ToolCollection(List.of(parentTool))).reactMemorySnapshot(List.of(parentMessage)).build();
        parent.setAttribute("runId", "main-run"); parent.setAttribute(ReActAgent.REACT_SYSTEM_PROMPT_KEY, "冻结的主 SYSTEM");
        SkillReviewRunner.Snapshot snapshot = runner.snapshot(parent);
        parentMessage.setContent("下一轮修改"); mutableSchema.put("type", "changed");
        SkillReviewExecution row = new SkillReviewExecution(); row.setReviewId("review"); row.setForkRequestId("fork-request"); row.setMaxSteps(6);
        SkillReviewState state = new SkillReviewState();
        AgentContext fork = runner.createContext(snapshot, row, state);
        Printer printer = mock(Printer.class); AgentResultWriter writer = mock(AgentResultWriter.class);
        // 即使误绑输出对象，复盘模式也应在集中事件边界停止输出。
        fork.setPrinter(printer); fork.setResultWriter(writer);
        assertNotSame(parent.getMemory(), fork.getMemory());
        assertNull(fork.getCurrentLlmInvocationId()); assertEquals("review", fork.getAttribute("runId"));
        assertEquals("主任务终答", fork.getMemory().get(0).getContent());
        Map<?, ?> frozenDefinition = (Map<?, ?>) snapshot.definitions().get("parent-business");
        assertEquals("object", ((Map<?, ?>) frozenDefinition.get("params")).get("type"));
        assertEquals(AgentRunMode.REVIEW_TOOLS, new HashSet<>(fork.getToolCollection().listTools().stream().map(BaseTool::name).toList()));
        assertEquals("已保存 Skill", agent.run(fork));
        assertEquals("改进流程\n", Files.readString(root.resolve("isolated-skill/SKILL.md")).substring(content.indexOf("原流程")));
        assertEquals(List.of("write", "read", "edit"), fork.getMemory().stream().filter(m -> m.getRole() == LlmMessageRole.TOOL).map(LlmMessage::getToolCallId).toList());
        assertTrue(fork.getToolExecutionResults().stream().allMatch(ToolExecutionResult::isSuccess));
        assertTrue(requests.get(0).getToolDefinitions().containsKey("parent-business"));
        for (ToolAskRequest request : requests) {
            assertEquals("冻结的主 SYSTEM", request.getMessages().get(0).getContent());
            assertEquals("SKILL_REVIEW", request.getAttributes().get(AgentRunMode.ATTRIBUTE));
            assertEquals("offline-test-model", request.getModel());
        }
        ledger.recordRunStarted(fork, "agent", "review"); ledger.recordRunFinished(fork, "COMPLETED", "done", null, null);
        verifyNoInteractions(recording, publisher, memory, compaction, printer, writer, collector, artifacts, mcp);
        assertTrue(parent.getMemory().isEmpty());
        assertEquals("SUCCESS", files.refresh(state).status());
    }

    @Test public void deniesBusinessRichAndScriptToolsBeforeAnyLedgerOrExecution() {
        DefaultToolRunner defaultRunner = new DefaultToolRunner();
        LedgerFactService ledger = mock(LedgerFactService.class); ReflectionTestUtils.setField(defaultRunner, "ledgerFactService", ledger);
        RichToolRunner runner = new RichToolRunner(defaultRunner);
        AgentContext fork = AgentContext.builder().runMode(AgentRunMode.SKILL_REVIEW).skillReviewState(new SkillReviewState()).build();
        for (String name : List.of("deep_search_tool", "session_search", "skill_script_runner_tool", "mcp_tool")) {
            BaseTool tool = mock(BaseTool.class); when(tool.name()).thenReturn(name);
            ToolInvokeRequest request = ToolInvokeRequest.builder().toolName(name).arguments(Map.of()).build();
            assertEquals("review_tool_denied", runner.run(new ToolCollection(List.of(tool)), request, fork).getErrorCode());
            assertEquals("review_tool_denied", defaultRunner.prepareResolved(tool, request, fork, "test", Instant.now()).getPreparationFailure().getErrorCode());
            verify(tool, never()).execute(any(), any());
        }
        verifyNoInteractions(ledger);
        assertEquals(AgentRunMode.MAIN, AgentContext.builder().build().getRunMode());
    }

    @Test public void runnerRejectsEmptyAnswerStepExhaustionAndUnrecoveredToolFailure() {
        ReActAgent agent = mock(ReActAgent.class); DefaultToolCollectionFactory factory = mock(DefaultToolCollectionFactory.class);
        when(factory.createSkillReviewTools(any())).thenReturn(new ToolCollection(List.of()));
        SkillReviewRunner runner = new SkillReviewRunner(agent, factory, mock(SkillReviewFiles.class));
        SkillReviewRunner.Snapshot snapshot = new SkillReviewRunner.Snapshot("s", "r", "run", "sys", List.of(), "", Map.of());
        SkillReviewExecution row = new SkillReviewExecution(); row.setReviewId("review"); row.setForkRequestId("fork"); row.setMaxSteps(1);
        doAnswer(call -> { AgentContext c = call.getArgument(0); c.appendMemory(LlmMessage.builder().role(LlmMessageRole.ASSISTANT).content("").build()); return ""; }).when(agent).run(any());
        assertFalse(runner.run(snapshot, row, new SkillReviewState()));
        doAnswer(call -> { AgentContext c = call.getArgument(0); c.setFinishReason("max_step_reached"); return "unfinished"; }).when(agent).run(any());
        assertFalse(runner.run(snapshot, row, new SkillReviewState()));
        doAnswer(call -> { AgentContext c = call.getArgument(0); c.getSkillReviewState().toolResult("workspace_edit", false); c.appendMemory(LlmMessage.builder().role(LlmMessageRole.ASSISTANT).content("已完成").build()); return "已完成"; }).when(agent).run(any());
        assertFalse(runner.run(snapshot, row, new SkillReviewState()));
    }

    private ToolCall toolCall(String id, String name, Map<String, Object> args) {
        return ToolCall.builder().id(id).type("function").function(ToolCall.Function.builder().name(name).arguments(com.alibaba.fastjson.JSON.toJSONString(args)).build()).build();
    }
}
