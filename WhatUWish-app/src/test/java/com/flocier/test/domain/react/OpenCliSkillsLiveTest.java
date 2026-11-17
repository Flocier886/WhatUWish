package com.flocier.test.domain.react;

import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
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
public class OpenCliSkillsLiveTest {
    @Autowired private ReactAgentWorkflowExecuteStrategy workflow;

    @BeforeClass
    public static void explicitOptIn() {
        Assume.assumeTrue("真实 OpenCLI 验收需显式开启", Boolean.getBoolean("opencli.skills.live"));
    }

    @Test
    public void searchesHackerNewsThroughSkillAndOpenCli() throws Exception {
        AgentContext context = run("请使用 OpenCLI 查找 Hacker News 上关于 OpenCLI 的帖子。"
                + "先查找并阅读适用的 skill，按其指导查命令帮助并实际搜索。"
                + "最后给出至少一条真实帖子的标题和链接，并注明所用来源。", 12);
        assertSuccessful(context, "skill_view");
        assertSuccessfulBrowser(context);
        assertBrowserResult(context, "hackernews", "search", "\\\"title\\\"");
    }

    @Test
    public void choosesSearchSourceThroughSmartSearchSkill() throws Exception {
        AgentContext context = run("请使用 OpenCLI 在合适的公开站点查找 OpenCLI 项目的讨论。"
                + "先通过技能目录找搜索选源的 skill，阅读后选一个真实可用的来源，"
                + "查帮助、实际搜索，并给出一条讨论的标题和链接。", 12);
        Assert.assertTrue("smart-search 没有被模型读取", context.getToolExecutionResults().stream()
                .anyMatch(result -> "skill_view".equals(result.getToolName()) && result.isSuccess()
                        && "smart-search".equals(result.getArguments().get("name"))));
        assertSuccessfulBrowser(context);
        assertBrowserResult(context, "hackernews", "search", "\\\"title\\\"");
    }

    @Test
    public void readsPublicPageThroughBrowserSkill() throws Exception {
        AgentContext context = run("请使用 OpenCLI 的浏览器原语访问 https://example.com/，"
                + "先查找并阅读 opencli-browser skill，然后打开页面、查看页面状态或提取正文，"
                + "最后根据实际页面内容告诉我网页标题。", 12);
        assertSuccessful(context, "skill_view");
        assertSuccessfulBrowser(context);
        assertBrowserResult(context, "browser", "state", "Example Domain");
    }

    private AgentContext run(String task, int maxStep) throws Exception {
        String id = "opencli-skill-live-" + UUID.randomUUID();
        AgentContext context = AgentContext.builder().requestId(id).sessionId(id)
                .query(task).task(task).maxStep(maxStep).build();
        workflow.execute(ExecuteCommandEntity.builder().aiAgentId("react-runtime")
                .sessionId(id).message(task).maxStep(maxStep).build(), context);
        System.out.println("OPENCLI_LIVE requestId=" + id + " runId=" + context.getAttribute("runId")
                + " finishReason=" + context.getFinishReason());
        System.out.println("OPENCLI_LIVE result=" + String.valueOf(context.getResult()).substring(0,
                Math.min(800, String.valueOf(context.getResult()).length())).replace('\n', ' '));
        List<ToolExecutionResult> results = context.getToolExecutionResults();
        if (results != null) for (ToolExecutionResult result : results) {
            String observation = result.getPreferredObservationText();
            System.out.println("OPENCLI_LIVE tool=" + result.getToolName() + " success=" + result.isSuccess()
                    + " errorCode=" + result.getErrorCode() + " observation="
                    + observation.substring(0, Math.min(500, observation.length())).replace('\n', ' '));
        }
        Assert.assertNotNull(context.getResult());
        return context;
    }

    private void assertSuccessful(AgentContext context, String name) {
        Assert.assertTrue(name + " 没有成功调用", context.getToolExecutionResults().stream()
                .anyMatch(result -> name.equals(result.getToolName()) && result.isSuccess()));
    }

    private void assertSuccessfulBrowser(AgentContext context) {
        Assert.assertTrue("OpenCLI 浏览器工具没有成功调用", context.getToolExecutionResults().stream()
                .anyMatch(result -> ("browser".equals(result.getToolName())
                        || "agent_browser".equals(result.getToolName())) && result.isSuccess()
                        && result.getPreferredObservationText().contains("\"ok\":true")));
    }

    private void assertBrowserResult(AgentContext context, String firstArg, String secondArg, String content) {
        Assert.assertTrue("未得到预期的 OpenCLI 内容：" + firstArg + " " + secondArg,
                context.getToolExecutionResults().stream().anyMatch(result ->
                        ("browser".equals(result.getToolName()) || "agent_browser".equals(result.getToolName()))
                                && result.isSuccess() && result.getArguments().get("args") instanceof List<?> args
                                && args.size() > 2 && firstArg.equals(args.get(0))
                                && secondArg.equals(args.get("browser".equals(firstArg) ? 2 : 1))
                                && result.getPreferredObservationText().contains(content)));
    }
}
