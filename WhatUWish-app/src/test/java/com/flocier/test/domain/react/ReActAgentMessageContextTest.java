package com.flocier.test.domain.react;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.RuntimeDependencies;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.react.prompt.ReactPromptTemplateRenderer;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillViewTool;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.convert.DomainMessageConverter;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import com.flocier.domain.agent.runtime.memory.SessionContextCompactionService;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import com.flocier.domain.agent.service.execute.react.step.ReactWorkflowRootNode;
import com.flocier.domain.agent.service.execute.react.step.ReactWorkflowRunAgentsNode;
import com.flocier.domain.agent.service.execute.react.step.factory.DefaultReactWorkflowStrategyFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

public class ReActAgentMessageContextTest {

    @Test
    public void workflowRootKeepsPreparedFileManifestForModelMessage() throws Exception {
        ReactWorkflowRootNode root = new ReactWorkflowRootNode();
        ReactWorkflowRunAgentsNode next = Mockito.mock(ReactWorkflowRunAgentsNode.class);
        ReflectionTestUtils.setField(root, "reactWorkflowRunAgentsNode", next);
        AgentContext context = AgentContext.builder().task("分析资料\ninputs/file-notes.md").build();
        ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                .sessionId("session").message("分析资料").maxStep(3).build();
        root.apply(command, DefaultReactWorkflowStrategyFactory.DynamicContext.builder()
                .agentContext(context).build());
        Assert.assertEquals("分析资料\ninputs/file-notes.md", context.getTask());
    }

    @Test
    public void materialManifestUsesUserUploadedFilesFence() {
        ReactAgentWorkflowExecuteStrategy workflow = new ReactAgentWorkflowExecuteStrategy();
        String content = ReflectionTestUtils.invokeMethod(workflow, "withFileManifest", "请分析文件",
                List.of(new RunWorkspaceService.MaterializedFile("file-id", "facts.pdf",
                        "application/pdf", 1024L, "inputs/file-id-facts.pdf")), true);
        Assert.assertTrue(content.contains("<user_uploaded_files>"));
        Assert.assertTrue(content.contains("inputs/file-id-facts.pdf"));
        Assert.assertTrue(content.contains("</user_uploaded_files>"));
        Assert.assertFalse(content.contains("storageKey"));
    }

    @Test
    public void continuesNativeMessagesWithStableSystemAndLedgerHistory() {
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.toolAsk(Mockito.any())).thenReturn(
                ToolAskResponse.builder().thought("调用两个工具")
                        .toolCalls(List.of(call("call-a", "tool_a"), call("call-b", "tool_b"))).build(),
                ToolAskResponse.builder().completed(true).thought("已完成").build());

        AgentContext context = context("先前讨论了报告", "请用工具完成任务");
        ReActAgent agent = agent(llm, new ReactorConfig());
        Assert.assertEquals("已完成", agent.run(context));

        ArgumentCaptor<ToolAskRequest> requests = ArgumentCaptor.forClass(ToolAskRequest.class);
        Mockito.verify(llm, Mockito.times(2)).toolAsk(requests.capture());
        List<LlmMessage> first = requests.getAllValues().get(0).getMessages();
        List<LlmMessage> second = requests.getAllValues().get(1).getMessages();

        Assert.assertEquals(List.of(LlmMessageRole.SYSTEM, LlmMessageRole.USER, LlmMessageRole.USER),
                first.stream().map(LlmMessage::getRole).toList());
        Assert.assertEquals(first.get(0).getContent(), second.get(0).getContent());
        Assert.assertFalse(first.get(0).getContent().contains("先前讨论了报告"));
        Assert.assertFalse(first.get(0).getContent().contains("请用工具完成任务"));
        Assert.assertTrue(first.get(0).getContent().contains("在同一次回复中同时请求这些工具"));
        Assert.assertTrue(first.get(0).getContent().contains("相同失败入参不反复重试"));
        Assert.assertTrue(first.get(0).getContent().contains("禁止重贴 tool 大输出"));
        Assert.assertTrue(first.get(1).getContent().contains("先前讨论了报告"));
        Assert.assertEquals("请用工具完成任务", first.get(2).getContent());
        Assert.assertEquals(List.of(LlmMessageRole.SYSTEM, LlmMessageRole.USER, LlmMessageRole.USER,
                        LlmMessageRole.ASSISTANT, LlmMessageRole.TOOL, LlmMessageRole.TOOL),
                second.stream().map(LlmMessage::getRole).toList());
        Assert.assertEquals(List.of("call-a", "call-b"),
                second.get(3).getToolCalls().stream().map(ToolCall::getId).toList());
        Assert.assertEquals("call-a", second.get(4).getToolCallId());
        Assert.assertEquals("observation-call-a", second.get(4).getContent());
        Assert.assertEquals("call-b", second.get(5).getToolCallId());
        Assert.assertEquals("observation-call-b", second.get(5).getContent());
        Assert.assertEquals(6, new DomainMessageConverter().toSpringMessages(second).size());
    }

    @Test
    public void injectsSkillIndexOnceIntoActualModelSystemMessage() {
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.toolAsk(Mockito.any())).thenReturn(
                ToolAskResponse.builder().completed(true).thought("已完成").build());
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(registry.listSkills()).thenReturn(List.of(
                SkillDefinition.builder().name("research-helper").description("查找研究资料")
                        .content("SECRET-BODY").build()));
        AgentContext context = context("", "查找研究资料");
        context.setToolCollection(new ToolCollection(List.of(new SkillViewTool(registry, new SkillPathGuard()))));
        Assert.assertEquals("已完成", agent(llm, new ReactorConfig()).run(context));
        ArgumentCaptor<ToolAskRequest> request = ArgumentCaptor.forClass(ToolAskRequest.class);
        Mockito.verify(llm).toolAsk(request.capture());
        String system = request.getValue().getMessages().get(0).getContent();
        Assert.assertTrue(system.contains("research-helper"));
        Assert.assertTrue(system.contains("skills_search"));
        Assert.assertFalse(system.contains("SECRET-BODY"));
        Assert.assertEquals(system.indexOf("<available_skills>"), system.lastIndexOf("<available_skills>"));
    }

    @Test
    public void omitsEmptyHistoryAndFallsBackFromDynamicSystemTemplate() {
        ReactorConfig config = new ReactorConfig();
        config.setReactSystemPromptMap("{\"default\":\"任务：{{task}}\\n记忆：{{memory}}\\n工具：{{tool_list}}\"}");
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.toolAsk(Mockito.any())).thenReturn(
                ToolAskResponse.builder().completed(true).thought("直接回答").build());

        AgentContext context = context("", "当前问题");
        Assert.assertEquals("直接回答", agent(llm, config).run(context));

        ArgumentCaptor<ToolAskRequest> request = ArgumentCaptor.forClass(ToolAskRequest.class);
        Mockito.verify(llm).toolAsk(request.capture());
        List<LlmMessage> messages = request.getValue().getMessages();
        Assert.assertEquals(List.of(LlmMessageRole.SYSTEM, LlmMessageRole.USER),
                messages.stream().map(LlmMessage::getRole).toList());
        Assert.assertFalse(messages.get(0).getContent().contains("{{task}}"));
        Assert.assertFalse(messages.get(0).getContent().contains("当前问题"));
        Assert.assertTrue(messages.get(0).getContent().contains("# 角色"));
        Assert.assertEquals("当前问题", messages.get(1).getContent());
    }

    @Test
    public void keepsDistinctTaskAndOriginalQueryInOneUserMessage() {
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.toolAsk(Mockito.any())).thenReturn(
                ToolAskResponse.builder().completed(true).thought("已完成").build());

        AgentContext context = context("", "帮我分析这份资料");
        context.setTask("分析资料并交付有来源的 Markdown 报告");
        Assert.assertEquals("已完成", agent(llm, new ReactorConfig()).run(context));

        ArgumentCaptor<ToolAskRequest> request = ArgumentCaptor.forClass(ToolAskRequest.class);
        Mockito.verify(llm).toolAsk(request.capture());
        List<LlmMessage> messages = request.getValue().getMessages();
        Assert.assertEquals(List.of(LlmMessageRole.SYSTEM, LlmMessageRole.USER),
                messages.stream().map(LlmMessage::getRole).toList());
        Assert.assertEquals("用户原话：\n帮我分析这份资料\n\n当前任务：\n分析资料并交付有来源的 Markdown 报告",
                messages.get(1).getContent());
        Assert.assertFalse(messages.get(0).getContent().contains("帮我分析这份资料"));
    }

    private ReActAgent agent(LLM llm, ReactorConfig config) {
        ReflectionTestUtils.setField(config, "reactModelName", "test-model");
        RuntimeDependencies dependencies = Mockito.mock(RuntimeDependencies.class);
        Mockito.when(dependencies.reactorConfig()).thenReturn(config);
        ReActAgent agent = new ReActAgent();
        ReflectionTestUtils.setField(agent, "llm", llm);
        ReflectionTestUtils.setField(agent, "runtimeDependencies", dependencies);
        ReflectionTestUtils.setField(agent, "promptTemplateRenderer", new ReactPromptTemplateRenderer());
        ReflectionTestUtils.setField(agent, "toolRunner", new ObservationToolRunner());
        SessionContextCompactionService compaction = Mockito.mock(SessionContextCompactionService.class);
        Mockito.when(compaction.applyIfNeeded(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(4));
        ReflectionTestUtils.setField(agent, "contextCompactionService", compaction);
        return agent;
    }

    private AgentContext context(String history, String query) {
        AgentContext context = AgentContext.builder()
                .requestId("request-message-context")
                .sessionId("session-message-context")
                .query(query).task(query).maxStep(3).build();
        if (history != null && !history.isBlank()) {
            context.setWorkingMemoryMessages(List.of(LlmMessage.builder().role(LlmMessageRole.USER)
                    .content("以下是旧会话的 Ledger 历史记录，仅供参考：\n" + history).build()));
        }
        context.setToolCollection(new ToolCollection(List.of(tool("tool_b"), tool("tool_a"))));
        return context;
    }

    private ToolCall call(String id, String toolName) {
        return ToolCall.builder().id(id).type("function")
                .function(ToolCall.Function.builder().name(toolName).arguments("{}").build()).build();
    }

    private BaseTool tool(String name) {
        return new BaseTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Object params() { return Map.of(); }
            @Override public Object execute(Object input, AgentContext context) { return null; }
        };
    }

    private static final class ObservationToolRunner implements ToolRunner {
        @Override
        public ToolExecutionResult run(ToolCollection collection, ToolInvokeRequest request, AgentContext context) {
            return ToolExecutionResult.builder()
                    .toolName(request.getToolName())
                    .toolCallId(request.getToolCallId())
                    .success(true)
                    .llmObservation("observation-" + request.getToolCallId())
                    .build();
        }
    }
}
