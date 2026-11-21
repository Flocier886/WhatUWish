package com.flocier.test.domain.react;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeBinding;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.summary.SummaryAgent;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.service.LedgerQueryService;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class ReActPythonToolFlowLiveTest {

    @Autowired
    private ReActAgent reActAgent;

    @Autowired
    private SummaryAgent summaryAgent;

    @Autowired
    private LedgerQueryService ledgerQueryService;

    @Test
    public void shouldRunReactArtifactSaveThenReadByStorageKey() {
        String suffix = UUID.randomUUID().toString();
        String sessionId = "react-python-artifact-flow-" + suffix;
        String artifactName = "artifact-react-e2e-" + suffix + ".txt";
        String marker = "artifact-react-e2e-marker-20260722-" + suffix;
        String task = "请严格按顺序完成以下任务：第一步，必须调用 artifact_save，将文本 '" + marker
                + "' 保存为 '" + artifactName + "'。第二步，从 artifact_save 返回的 observation 中取得 artifactId，"
                + "必须调用 artifact_read(artifactId=...) 回读该文件，禁止使用 path、name 或 storageKey 参数。"
                + "第三步，确认读取结果包含原始文本后，用中文简短说明完成情况。";
        AgentContext context = AgentContext.builder()
                .requestId("react-python-artifact-flow-request-" + suffix)
                .sessionId(sessionId)
                .query(task)
                .task(task)
                .maxStep(4)
                .build();

        String reactResult = reActAgent.run(context);
        String finalSummary = summaryAgent.run(context);

        Assert.assertNotNull(reactResult);
        Assert.assertFalse(reactResult.isBlank());
        Assert.assertNotNull(finalSummary);
        Assert.assertFalse(finalSummary.isBlank());
        Assert.assertNotNull(context.getAttribute("runId"));

        Assert.assertNotNull(context.getToolExecutionResults());
        Assert.assertFalse(context.getToolExecutionResults().isEmpty());
        ToolExecutionResult saveResult = findFirstTool(context, "artifact_save");
        ToolExecutionResult readResult = findFirstTool(context, "artifact_read");
        Assert.assertNotNull(saveResult);
        Assert.assertTrue(saveResult.isSuccess());
        Assert.assertTrue(saveResult.getLlmObservation().contains("artifactId"));
        Assert.assertNotNull(readResult);
        Assert.assertTrue(readResult.isSuccess());
        Assert.assertTrue(readResult.getOutputText().contains(marker));
        ArtifactRuntimeBinding runtimeBinding = context.requireArtifactRuntimeRegistry().listBindings().stream()
                .filter(binding -> binding.getDescriptor() != null
                        && artifactName.equals(binding.getDescriptor().getFileName())
                        && binding.getDescriptor().getStorageKey() != null
                        && !binding.getDescriptor().getStorageKey().isBlank())
                .findFirst()
                .orElse(null);
        Assert.assertNotNull(runtimeBinding);

        Object runIdValue = context.getAttribute("runId");
        String runId = runIdValue == null ? null : runIdValue.toString();
        List<ArtifactLedgerEntity> ledgerArtifacts = ledgerQueryService
                .queryArtifactsByRunIdAndToolCallId(runId, saveResult.getToolCallId());
        Assert.assertEquals(1, ledgerArtifacts.size());
        ArtifactLedgerEntity ledgerArtifact = ledgerArtifacts.get(0);
        Assert.assertEquals(runtimeBinding.getDescriptor().getArtifactId(), ledgerArtifact.getArtifactId());
        Assert.assertEquals(runtimeBinding.getDescriptor().getStorageKey(), ledgerArtifact.getStorageKey());
        Assert.assertEquals("artifact_save", ledgerArtifact.getSourceType());
        Assert.assertTrue(ledgerArtifact.getIdempotencyKey().startsWith("artifact:"));
        Assert.assertEquals(57, ledgerArtifact.getIdempotencyKey().length());

        Assert.assertTrue(context.getMemory().stream().anyMatch(message ->
                message.getRole() == LlmMessageRole.ASSISTANT
                        && message.getToolCalls() != null
                        && !message.getToolCalls().isEmpty()));
        Assert.assertTrue(context.getMemory().stream().anyMatch(message ->
                message.getRole() == LlmMessageRole.TOOL
                        && message.getToolCallId() != null
                        && !message.getToolCallId().isBlank()));

        List<String> trace = context.getAttribute(ReActAgent.REACT_TRACE_KEY);
        Assert.assertNotNull(trace);
        Assert.assertFalse(trace.isEmpty());
        Assert.assertNotNull(context.getAttribute(SummaryAgent.FINAL_SUMMARY_KEY));
        Assert.assertNotNull(context.getAttribute(ReActAgent.REACT_LAST_PROMPT_KEY));
    }

    @Test
    public void shouldRecoverAfterMissingFileReadAndFinishTask() {
        String sessionId = "react-python-tool-recovery-missing-file-" + UUID.randomUUID();
        String task = "请先读取 WhatUWish-tools-python/README-not-exists.md。"
                + "如果读取失败，请根据工具返回的信息自行改用 directory_list 或 file_search 定位真实的 README.md，"
                + "然后读取真实 README，整理一段中文摘要，并保存为 recovery-summary.txt，最后告诉我保存结果。";
        AgentContext context = AgentContext.builder()
                .requestId("react-python-tool-recovery-missing-file-request-10084")
                .sessionId(sessionId)
                .query(task)
                .task(task)
                .maxStep(7)
                .build();

        String reactResult = reActAgent.run(context);
        String finalSummary = summaryAgent.run(context);

        Assert.assertNotNull(reactResult);
        Assert.assertFalse(reactResult.isBlank());
        Assert.assertNotNull(finalSummary);
        Assert.assertFalse(finalSummary.isBlank());

        ToolExecutionResult failedRead = findFirstTool(context, "file_read");
        Assert.assertNotNull(failedRead);
        Assert.assertFalse(failedRead.isSuccess());
        Assert.assertEquals("failed", failedRead.getStatus());
        Assert.assertTrue(failedRead.getLlmObservation().contains("directory_list"));
        Assert.assertTrue(failedRead.getLlmObservation().contains("file_search"));

        Assert.assertTrue(hasSuccessfulTool(context, "directory_list") || hasSuccessfulTool(context, "file_search"));
        Assert.assertTrue(hasSuccessfulSaveTool(context));
    }

    @Test
    public void shouldRecoverAfterEmptySearchResultAndContinueTask() {
        String sessionId = "react-python-tool-recovery-empty-search-" + UUID.randomUUID();
        String task = "请先在 WhatUWish-tools-python/app/tools 目录下使用 file_search 搜索关键词 definitely_no_such_symbol_12345。"
                + "如果没有结果，请根据工具返回的信息改用 directory_list 或直接读取 README.md，"
                + "最后整理一段中文说明并保存为 empty-search-summary.txt。";
        AgentContext context = AgentContext.builder()
                .requestId("react-python-tool-recovery-empty-search-request-10084")
                .sessionId(sessionId)
                .query(task)
                .task(task)
                .maxStep(7)
                .build();

        String reactResult = reActAgent.run(context);
        String finalSummary = summaryAgent.run(context);

        Assert.assertNotNull(reactResult);
        Assert.assertFalse(reactResult.isBlank());
        Assert.assertNotNull(finalSummary);
        Assert.assertFalse(finalSummary.isBlank());

        ToolExecutionResult fileSearchResult = findFirstTool(context, "file_search");
        Assert.assertNotNull(fileSearchResult);
        Assert.assertTrue(fileSearchResult.isSuccess());
        Assert.assertEquals("partial_success", fileSearchResult.getStatus());
        Assert.assertTrue(fileSearchResult.getLlmObservation().contains("调整关键词")
                || fileSearchResult.getLlmObservation().contains("目录结构"));

        Assert.assertTrue(hasSuccessfulTool(context, "directory_list") || hasSuccessfulTool(context, "file_read"));
        Assert.assertTrue(hasSuccessfulSaveTool(context));
    }

    private boolean hasSuccessfulTool(AgentContext context, String toolName) {
        return context.getToolExecutionResults().stream()
                .anyMatch(item -> toolName.equals(item.getToolName()) && item.isSuccess() && "python".equals(item.getSourceType()));
    }

    private boolean hasSuccessfulSaveTool(AgentContext context) {
        return context.getToolExecutionResults().stream()
                .anyMatch(item -> item.isSuccess()
                        && "python".equals(item.getSourceType())
                        && ("artifact_save".equals(item.getToolName()) || "file_write".equals(item.getToolName())));
    }

    private ToolExecutionResult findFirstTool(AgentContext context, String toolName) {
        return context.getToolExecutionResults().stream()
                .filter(item -> toolName.equals(item.getToolName()))
                .findFirst()
                .orElse(null);
    }
}
