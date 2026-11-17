package com.flocier.test.domain.memory;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.domain.agent.runtime.memory.review.SkillReviewOptions;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import com.flocier.domain.ledger.service.ExecutionReplayService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 显式开启的真实验收：真实模型、数据库与文件，不替换运行时组件。
 * 仅测试进程缩短复盘间隔；Skill 使用独立临时根，结束后定向清理本次会话。
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class SkillReviewLiveTest {
    private static final Path SKILL_ROOT = temporaryRoot();
    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String SESSION = "skill-review-live-" + SUFFIX;
    private static final String EMPTY_SESSION = "skill-review-empty-" + SUFFIX;
    private static final List<String> TABLES = List.of("runtime_event_ledger", "artifact_ledger",
            "tool_invocation_ledger", "llm_invocation_ledger", "ai_agent_working_memory_compaction",
            "ai_agent_working_memory_message", "ai_agent_working_memory_turn",
            "memory_skill_review_execution", "run_ledger", "session_ledger");

    @Autowired private ReactAgentWorkflowExecuteStrategy workflow;
    @Autowired private SkillRegistry registry;
    @Autowired private SkillReviewOptions options;
    @Autowired private SessionWorkingMemoryRepository memory;
    @Autowired private ExecutionReplayService replay;
    @Autowired private DataSource dataSource;

    @BeforeClass
    public static void explicitOptIn() {
        Assume.assumeTrue("真实模型验收需显式开启", Boolean.getBoolean("memory.skill.review.live"));
    }

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry properties) {
        properties.add("memory.skill-review.nudge-interval", () -> "1");
        properties.add("memory.skill-review.timeout-seconds", () -> "240");
        properties.add("reactor.skill.runtime", () -> JSON.toJSONString(Map.of(
                "enabled", true, "reactEnabled", true, "masterStyleToolsEnabled", true,
                "legacySkillExecuteToolEnabled", false, "directories", List.of(SKILL_ROOT.toString()))));
    }

    @Test
    public void realMainReviewCreateUpdateAndRead() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Path project = projectRoot();
        Path evidencePath = project.resolve("temp_work/memory-skill-review-live-evidence.json");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sessionId", SESSION);
        evidence.put("emptySessionId", EMPTY_SESSION);
        evidence.put("testInterval", options.getNudgeInterval());
        evidence.put("maxSteps", options.getMaxSteps());
        evidence.put("timeoutSeconds", options.getTimeoutSeconds());
        List<Map<String, Object>> rounds = new ArrayList<>();
        evidence.put("rounds", rounds);
        try {
            // 使用正式幂等 SQL 建表，保留表结构，仅清理本次验收业务行。
            Path schema = project.resolve("docs/sql/memory-skill-review.sql");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new FileSystemResource(schema));
            // Windows 默认字符集可能为 GBK，必须按源 SQL 的 UTF-8 读取中文注释。
            populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
            populator.execute(dataSource);
            evidence.put("database", jdbc.queryForObject("SELECT DATABASE()", String.class));
            List<Map<String, Object>> columns = jdbc.queryForList("SHOW FULL COLUMNS FROM memory_skill_review_execution");
            Assert.assertEquals(21, columns.size());
            // 逐字核验，避免乱码仍含汉字而被“包含中文”的宽泛检查放过。
            String sql = Files.readString(schema, StandardCharsets.UTF_8);
            var fieldComments = Pattern.compile("(?m)^    ([a-z_]+) .*COMMENT '([^']*)'").matcher(sql);
            Map<String, String> expectedComments = new LinkedHashMap<>();
            while (fieldComments.find()) expectedComments.put(fieldComments.group(1), fieldComments.group(2));
            Assert.assertEquals(columns.size(), expectedComments.size());
            for (Map<String, Object> column : columns) {
                Assert.assertEquals("字段注释：" + column.get("Field"),
                        expectedComments.get(String.valueOf(column.get("Field"))), column.get("Comment"));
            }
            var tableComment = Pattern.compile("COMMENT='([^']*)'").matcher(sql);
            Assert.assertTrue(tableComment.find());
            Assert.assertEquals(tableComment.group(1), jdbc.queryForObject(
                    "SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='memory_skill_review_execution'",
                    String.class));
            evidence.put("exactChineseCommentsVerified", true);
            evidence.put("tableColumnCount", columns.size());
            evidence.put("tableIndexes", jdbc.queryForList("SHOW INDEX FROM memory_skill_review_execution").stream()
                    .map(row -> row.get("Key_name")).distinct().toList());
            Assert.assertEquals(1, options.getNudgeInterval());

            AgentContext first = execute(SESSION, "create", "请把下面已实践成功的发布健康核验经验整理成不超过六条的可复用流程，"
                    + "这轮只整理知识，不执行部署、不查询外部信息：发布前保存当前版本及回滚命令，先检查依赖和健康端点，"
                    + "小流量发布后同时核对错误率、延迟与业务成功率；任一指标异常立即停止扩量并回滚，回滚后再核验业务。"
                    + "流程适用于同类服务发布，不要写成某个项目的流水账。");
            Map<String, Object> created = awaitReview(jdbc, first);
            rounds.add(checkRound(jdbc, first, created));
            Assert.assertEquals("SUCCESS", created.get("status"));
            Assert.assertTrue("真实复盘未产生 Skill 文件", ((Number) created.get("changed_file_count")).intValue() > 0);
            Assert.assertEquals("SUCCESS", created.get("refresh_status"));
            Assert.assertEquals("预期沉淀一个类别级 Skill", 1, registry.listSkills().size());
            SkillDefinition skill = registry.listSkills().get(0);
            Path skillFile = skill.getBasePath().resolve("SKILL.md");
            String original = Files.readString(skillFile);
            evidence.put("skillName", skill.getName());
            evidence.put("createdContent", original);

            AgentContext second = execute(SESSION, "update", "请调用 skills_search 查找可用 Skill，"
                    + "再通过 skill_view 读取 " + skill.getName() + " 的 SKILL.md。"
                    + "在已有流程上补充一条经实践验证的重要改进：回滚成功不能只看单次健康探测，"
                    + "至少连续三次探测成功且确认版本号已恢复后才能宣布恢复。"
                    + "请结合读取的流程简短答复这条补充，不执行部署，也不写文件。");
            Map<String, Object> updated = awaitReview(jdbc, second);
            rounds.add(checkRound(jdbc, second, updated));
            Assert.assertTrue("后续主任务没有成功发现 Skill", second.getToolExecutionResults().stream()
                    .anyMatch(t -> "skills_search".equals(t.getToolName()) && t.isSuccess()));
            Assert.assertTrue("后续主任务没有成功读取 Skill", second.getToolExecutionResults().stream()
                    .anyMatch(t -> "skill_view".equals(t.getToolName()) && t.isSuccess()
                            && t.getLlmObservation().contains(skill.getName())));
            Assert.assertEquals("SUCCESS", updated.get("status"));
            Assert.assertTrue("真实复盘没有更新已有 Skill", ((Number) updated.get("changed_file_count")).intValue() > 0);
            Assert.assertEquals("SUCCESS", updated.get("refresh_status"));
            Assert.assertEquals("应优先补充同一类别 Skill", 1, registry.listSkills().size());
            String revised = Files.readString(skillFile);
            Assert.assertNotEquals(original, revised);
            Assert.assertTrue("没有保留新增的三次探测规则", revised.contains("三次") || revised.contains("3") || revised.contains("three"));
            evidence.put("updatedContent", revised);

            AgentContext empty = execute(EMPTY_SESSION, "empty", "你好，这轮只需回复“你好”，没有其他任务。");
            Map<String, Object> unchanged = awaitReview(jdbc, empty);
            rounds.add(checkRound(jdbc, empty, unchanged));
            Assert.assertEquals("SUCCESS", unchanged.get("status"));
            Assert.assertEquals(0, ((Number) unchanged.get("changed_file_count")).intValue());
            Assert.assertEquals("NOT_NEEDED", unchanged.get("refresh_status"));
            Assert.assertEquals(revised, Files.readString(skillFile));
            evidence.put("verified", true);
        } finally {
            // 等待过的终态是清理前提；若断言提前失败且仍在运行，保留现场供排查。
            long running = jdbc.queryForObject("SELECT COUNT(*) FROM memory_skill_review_execution "
                    + "WHERE session_id IN (?, ?) AND status='RUNNING'", Long.class, SESSION, EMPTY_SESSION);
            evidence.put("runningAtCleanup", running);
            if (running == 0) {
                for (String table : TABLES) {
                    jdbc.update("DELETE FROM " + table + " WHERE session_id IN (?, ?)", SESSION, EMPTY_SESSION);
                }
                // 临时根由本测试创建，解析后逐项确认边界，避免清理用户 Skill。
                try (var paths = Files.walk(SKILL_ROOT)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Assert.assertTrue(path.toAbsolutePath().normalize().startsWith(SKILL_ROOT.toAbsolutePath().normalize()));
                        Files.deleteIfExists(path);
                    }
                }
                registry.refresh();
                evidence.put("testDataCleaned", true);
            }
            Files.createDirectories(evidencePath.getParent());
            Files.writeString(evidencePath, JSON.toJSONString(evidence, true), StandardCharsets.UTF_8);
        }
    }

    /** 正式成功收尾自动调度后台复盘，不直接调用调度器或拼造父消息。 */
    private AgentContext execute(String session, String stage, String query) throws Exception {
        AgentContext context = AgentContext.builder().sessionId(session)
                .requestId("skill-live-" + stage + "-" + SUFFIX).query(query).maxStep(5).build();
        workflow.execute(ExecuteCommandEntity.builder().aiAgentId("react-live")
                .sessionId(session).message(query).maxStep(5).build(), context);
        Assert.assertNotNull(context.getResult());
        return context;
    }

    /** 只轮询本次请求，最长为正式复盘预算加少量落库余量。 */
    private Map<String, Object> awaitReview(JdbcTemplate jdbc, AgentContext context) throws InterruptedException {
        long deadline = System.nanoTime() + (options.getTimeoutSeconds() + 15L) * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM memory_skill_review_execution WHERE trigger_request_id=?",
                    context.getRequestId());
            if (!rows.isEmpty() && !"RUNNING".equals(rows.get(0).get("status"))) return rows.get(0);
            Thread.sleep(250);
        }
        throw new AssertionError("复盘未在预算内落库终态：" + context.getRequestId());
    }

    /** 真实数据库及 Replay 检查：后台标识不进入主账本，主记忆等于 ReAct 快照。 */
    private Map<String, Object> checkRound(JdbcTemplate jdbc, AgentContext parent, Map<String, Object> row) {
        String runId = parent.getAttribute("runId");
        Assert.assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM run_ledger WHERE run_id=?", String.class, runId));
        List<LlmMessage> persisted = memory.loadReadyMessages(parent.getSessionId(), "live-inspect");
        List<LlmMessage> snapshot = parent.getReactMemorySnapshot();
        Assert.assertEquals(snapshot.size(), persisted.size());
        for (int i = 0; i < snapshot.size(); i++) {
            // 数据库会补 originMessageKey；只比较模型实际使用的原生消息合同。
            Assert.assertEquals(snapshot.get(i).getRole(), persisted.get(i).getRole());
            Assert.assertEquals(snapshot.get(i).getContent(), persisted.get(i).getContent());
            Assert.assertEquals(snapshot.get(i).getToolCallId(), persisted.get(i).getToolCallId());
            Assert.assertEquals(snapshot.get(i).getToolCalls(), persisted.get(i).getToolCalls());
        }
        Assert.assertFalse(JSON.toJSONString(persisted).contains("This is background skill maintenance."));
        for (String table : List.of("run_ledger", "llm_invocation_ledger", "tool_invocation_ledger", "runtime_event_ledger", "artifact_ledger")) {
            Assert.assertEquals("后台污染主表：" + table, Long.valueOf(0), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE run_id=?", Long.class, row.get("review_id")));
        }
        Assert.assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_working_memory_message WHERE request_id=?",
                Long.class, row.get("fork_request_id")));
        Assert.assertFalse("主 Run 缺少真实回放", replay.replayRun(runId).getEntries().isEmpty());
        Assert.assertTrue("后台不应产生用户回放", replay.replayRun(String.valueOf(row.get("review_id"))).getEntries().isEmpty());
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("mainRunId", runId);
        result.put("mainResult", parent.getResult());
        result.put("mainTools", parent.getToolExecutionResults().stream().map(t -> Map.of("name", t.getToolName(), "success", t.isSuccess())).toList());
        result.put("memoryAndLedgerIsolationVerified", true);
        return result;
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("skill-review-live-").toAbsolutePath(); }
        catch (Exception e) { throw new IllegalStateException("无法创建真实验收临时 Skill 根", e); }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        return Files.isDirectory(current.resolve("docs/sql")) ? current : current.getParent();
    }
}
