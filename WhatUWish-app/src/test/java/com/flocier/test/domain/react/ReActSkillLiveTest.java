package com.flocier.test.domain.react;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
public class ReActSkillLiveTest {

    @Autowired
    private ReactAgentWorkflowExecuteStrategy workflow;

    @BeforeClass
    public static void explicitOptIn() {
        Assume.assumeTrue("真实模型验收需显式开启", Boolean.getBoolean("skill.migration.live"));
    }

    @Test
    public void shouldRunReactFlowWithMasterStyleSkillTools() throws Exception {
        String sessionId = "skill-read-" + UUID.randomUUID();
        String task = "请查找 master-style-fixture 这个 skill，读取它的说明和 references/guide.md，"
                + "最后简要总结它的用途，并引用参考资料中的一个具体信息。";
        AgentContext context = AgentContext.builder()
                .requestId("skill-read-" + UUID.randomUUID())
                .sessionId(sessionId)
                .query(task)
                .task(task)
                .maxStep(10)
                .build();

        assertReactFinished(context);

        Assert.assertNotNull(context.getToolExecutionResults());
        Assert.assertFalse(context.getToolExecutionResults().isEmpty());
        Assert.assertTrue(hasSuccessfulTool(context, "skill_view"));
        Assert.assertTrue(hasSuccessfulTool(context, "workspace_read"));
        assertNoLegacyTools(context);

        assertToolCallingMemory(context);
        assertTraceRecorded(context);
    }

    @Test
    public void shouldRunReactFlowAndExecuteRegisteredSkillScript() throws Exception {
        String sessionId = "skill-script-" + UUID.randomUUID();
        String task = "请找到并阅读 master-style-fixture skill，按其中的说明用 bash 执行 summarize 脚本，"
                + "处理文字“这是一次 skill runtime 迁移测试”，并交付脚本生成的 summary.txt 文件。";
        AgentContext context = AgentContext.builder()
                .requestId("skill-script-" + UUID.randomUUID())
                .sessionId(sessionId)
                .query(task)
                .task(task)
                .maxStep(10)
                .build();

        assertReactFinished(context);

        ToolExecutionResult scriptResult = findFirstTool(context, "bash");
        Assert.assertNotNull(scriptResult);
        Assert.assertTrue(scriptResult.isSuccess());
        assertNoLegacyTools(context);
        Assert.assertNotNull(scriptResult.getArtifactRefs());
        Assert.assertFalse(scriptResult.getArtifactRefs().isEmpty());
    }

    private void assertReactFinished(AgentContext context) throws Exception {
        workflow.execute(ExecuteCommandEntity.builder().aiAgentId("react-runtime")
                .sessionId(context.getSessionId()).message(context.getTask()).maxStep(10).build(), context);
        Assert.assertNotNull(context.getResult());
        Assert.assertNotNull(context.getAttribute("runId"));
    }

    private void assertToolCallingMemory(AgentContext context) {
        Assert.assertTrue(context.getMemory().stream().anyMatch(message ->
                message.getRole() == LlmMessageRole.ASSISTANT
                        && message.getToolCalls() != null
                        && !message.getToolCalls().isEmpty()));
        Assert.assertTrue(context.getMemory().stream().anyMatch(message ->
                message.getRole() == LlmMessageRole.TOOL
                        && message.getToolCallId() != null
                        && !message.getToolCallId().isBlank()));
    }

    private void assertTraceRecorded(AgentContext context) {
        List<String> trace = context.getAttribute(ReActAgent.REACT_TRACE_KEY);
        Assert.assertNotNull(trace);
        Assert.assertFalse(trace.isEmpty());
        Assert.assertNotNull(context.getAttribute(ReActAgent.REACT_LAST_PROMPT_KEY));
    }

    private void assertNoLegacyTools(AgentContext context) {
        for (String name : List.of("skill_tool", "skill_read_tool", "skill_list_directory_tool",
                "skill_glob_tool", "skill_grep_tool", "skill_script_runner_tool")) {
            Assert.assertFalse("旧 skill 工具仍被调用：" + name, hasTool(context, name));
        }
    }

    private boolean hasSuccessfulTool(AgentContext context, String toolName) {
        return context.getToolExecutionResults().stream()
                .anyMatch(item -> toolName.equals(item.getToolName()) && item.isSuccess());
    }

    private boolean hasTool(AgentContext context, String toolName) {
        return context.getToolExecutionResults().stream()
                .anyMatch(item -> toolName.equals(item.getToolName()));
    }

    private ToolExecutionResult findFirstTool(AgentContext context, String toolName) {
        return context.getToolExecutionResults().stream()
                .filter(item -> toolName.equals(item.getToolName()))
                .findFirst()
                .orElse(null);
    }
}
