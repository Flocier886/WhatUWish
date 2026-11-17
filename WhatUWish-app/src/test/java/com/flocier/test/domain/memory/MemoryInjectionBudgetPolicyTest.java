package com.flocier.test.domain.memory;

import com.flocier.domain.agent.model.entity.SessionHistoryMemory;
import com.flocier.domain.agent.model.entity.SessionMemoryView;
import com.flocier.domain.agent.runtime.memory.MemoryInjectionBudgetPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class MemoryInjectionBudgetPolicyTest {

    @Test
    public void shouldKeepNewestCompleteRunAndDiscardOlderRunAsWholeBlock() {
        MemoryInjectionBudgetPolicy policy = new MemoryInjectionBudgetPolicy(1, 1, 1, 1, 1400);
        SessionMemoryView budgeted = policy.apply(view(List.of(
                run("run-old", "旧历史。".repeat(120), List.of(), "", ""),
                run("run-new", "新历史。".repeat(120), List.of(), "", "")
        )));

        Assert.assertEquals(1, budgeted.getRetainedRunHistories().size());
        Assert.assertEquals("run-new", budgeted.getRetainedRunHistories().get(0).getRunId());
        Assert.assertTrue(budgeted.getInjectionText().contains("### Run run-new"));
        Assert.assertFalse(budgeted.getInjectionText().contains("### Run run-old"));
        Assert.assertTrue(budgeted.getInjectionText().length() <= 1400);
    }

    @Test
    public void shouldIndexReusableArtifactAndFailureOnlyFromDiscardedRuns() {
        MemoryInjectionBudgetPolicy policy = new MemoryInjectionBudgetPolicy(1, 1, 1, 1, 2200);
        SessionHistoryMemory.ArtifactReference artifact = SessionHistoryMemory.ArtifactReference.builder()
                .artifactId("artifact-old")
                .name("old-report.md")
                .type("file")
                .storageRef("artifacts/old-report.md")
                .usageSummary("可复用的旧报告")
                .build();
        SessionHistoryMemory.ToolHistory failedTool = SessionHistoryMemory.ToolHistory.builder()
                .toolInvocationId("tool-old")
                .toolName("export_report")
                .status("FAILED")
                .errorMessage("旧环境缺少模板")
                .observation("不应作为补充索引的普通 observation")
                .artifacts(List.of(artifact))
                .build();
        SessionMemoryView budgeted = policy.apply(view(List.of(
                run("run-old", "旧 Run。".repeat(160), List.of(failedTool), "", ""),
                run("run-new", "新 Run。".repeat(230), List.of(), "", "")
        )));

        Assert.assertTrue(budgeted.getHistoricalIndexes().stream()
                .anyMatch(item -> item.getArtifact() != null && "artifact-old".equals(item.getArtifact().getArtifactId())));
        Assert.assertTrue(budgeted.getHistoricalIndexes().stream()
                .anyMatch(item -> "失败经验".equals(item.getIndexType()) && item.getSummary().contains("缺少模板")));
        Assert.assertTrue(budgeted.getInjectionText().contains("artifact-old"));
        Assert.assertFalse(budgeted.getInjectionText().contains("不应作为补充索引的普通 observation"));
    }

    @Test
    public void shouldUseSemanticDowngradeForOversizedLatestRun() {
        MemoryInjectionBudgetPolicy policy = new MemoryInjectionBudgetPolicy(1, 1, 1, 1, 700);
        SessionHistoryMemory.ToolHistory tool = SessionHistoryMemory.ToolHistory.builder()
                .toolInvocationId("tool-large")
                .toolName("large_tool")
                .status("SUCCESS")
                .arguments("参数。".repeat(400))
                .observation("观察结果。".repeat(500))
                .artifacts(List.of())
                .build();
        SessionMemoryView budgeted = policy.apply(view(List.of(
                run("run-large", "总结。".repeat(500), List.of(tool), "", "")
        )));

        Assert.assertTrue(budgeted.getInjectionText().length() <= 700);
        Assert.assertTrue(budgeted.getInjectionText().contains("已按预算省略"));
        Assert.assertTrue(budgeted.getInjectionText().contains("observation")
                || budgeted.getInjectionText().contains("详细 observation"));
    }

    @Test
    public void shouldReturnEmptyPromptWhenNoLedgerHistoryAvailable() {
        MemoryInjectionBudgetPolicy policy = new MemoryInjectionBudgetPolicy(1, 1, 1, 1, 700);
        SessionMemoryView budgeted = policy.apply(SessionMemoryView.builder().sessionId("session-empty").build());
        Assert.assertEquals("", budgeted.getInjectionText());
    }

    private SessionMemoryView view(List<SessionHistoryMemory.RunHistory> runs) {
        return SessionMemoryView.builder()
                .sessionId("session-budget")
                .sessionHistory(new SessionHistoryMemory("session-budget", runs))
                .build();
    }

    private SessionHistoryMemory.RunHistory run(String runId, String summary,
                                                List<SessionHistoryMemory.ToolHistory> tools,
                                                String errorCode, String errorMessage) {
        return SessionHistoryMemory.RunHistory.builder()
                .runId(runId)
                .status("COMPLETED")
                .summary(summary)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .cycles(List.of(SessionHistoryMemory.CycleHistory.builder()
                        .llmInvocationId("llm-" + runId)
                        .responseText("模型决策。".repeat(80))
                        .tools(tools)
                        .build()))
                .orphanTools(List.of())
                .unassociatedArtifacts(List.of())
                .build();
    }
}
