package com.flocier.domain.agent.runtime.memory;

import com.flocier.domain.agent.model.entity.SessionHistoryMemory;
import com.flocier.domain.agent.model.entity.SessionMemoryView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 连续 Ledger history 的字符预算策略。
 *
 * 先保留最新完整 Run，再对单个最新 Run 在 Cycle/字段边界降级；只从被淘汰 Run 产生高价值索引。
 */
@Slf4j
@Component
public class MemoryInjectionBudgetPolicy {

    private static final String HISTORY_HEADER = "以下是同一会话的账本执行历史，请按 Run、LLM、工具结果和产物引用理解前因后果：\n\n";

    private final int maxHistoryChars;
    private final int maxIndexes;
    private final int maxIndexChars;
    private final SessionHistoryMemoryRenderer renderer;

    /** 供已有非 Spring 测试与手工构造场景使用；其最后一个参数视为字符预算。 */
    public MemoryInjectionBudgetPolicy(int ignoredRunSummaries, int ignoredFacts, int ignoredTasks,
                                       int ignoredArtifacts, int maxHistoryChars) {
        this(maxHistoryChars, 6, Math.max(120, maxHistoryChars / 5), new SessionHistoryMemoryRenderer());
    }

    @Autowired
    public MemoryInjectionBudgetPolicy(
            @Value("${memory.ledger.max-history-chars:${memory.ledger.max-prompt-chars:12000}}") int maxHistoryChars,
            @Value("${memory.ledger.max-historical-indexes:6}") int maxIndexes,
            @Value("${memory.ledger.max-historical-index-chars:1800}") int maxIndexChars,
            SessionHistoryMemoryRenderer renderer) {
        this.maxHistoryChars = Math.max(500, maxHistoryChars);
        this.maxIndexes = Math.max(0, maxIndexes);
        this.maxIndexChars = Math.max(0, maxIndexChars);
        this.renderer = renderer;
    }

    public SessionMemoryView apply(SessionMemoryView rawView) {
        if (rawView == null || rawView.getSessionHistory() == null
                || rawView.getSessionHistory().getRuns() == null || rawView.getSessionHistory().getRuns().isEmpty()) {
            return emptyResult(rawView);
        }

        List<SessionHistoryMemory.RunHistory> allRuns = rawView.getSessionHistory().getRuns();
        List<SessionHistoryMemory.RunHistory> retained = new ArrayList<>();
        List<SessionHistoryMemory.RunHistory> discarded = new ArrayList<>();
        List<String> renderedRuns = new ArrayList<>();
        int used = HISTORY_HEADER.length();

        for (int index = allRuns.size() - 1; index >= 0; index--) {
            SessionHistoryMemory.RunHistory run = allRuns.get(index);
            String fullBlock = renderer.renderRun(run, SessionHistoryMemoryRenderer.RenderOptions.full());
            int fullLength = blockLength(fullBlock);
            if (used + fullLength <= maxHistoryChars) {
                retained.add(0, run);
                renderedRuns.add(0, fullBlock);
                used += fullLength;
                continue;
            }
            if (retained.isEmpty()) {
                String degraded = renderLatestRunWithinBudget(run, maxHistoryChars - used);
                retained.add(run);
                renderedRuns.add(degraded);
                used += blockLength(degraded);
            }
            discarded.add(0, run);
        }

        List<SessionHistoryMemory.HistoricalIndexItem> indexes = buildIndexes(discarded, retained);
        String historyText = joinBlocks(renderedRuns);
        int remaining = maxHistoryChars - HISTORY_HEADER.length() - historyText.length();
        String indexText = renderer.renderIndexes(indexes, Math.min(maxIndexChars, Math.max(0, remaining)));
        String injectionText = renderPrompt(historyText, indexText);

        log.info("continuous ledger memory budget applied, sessionId={}, retainedRuns={}, discardedRuns={}, indexCount={}, promptChars={}",
                rawView.getSessionId(), retained.size(), discarded.size(), indexes.size(), injectionText.length());
        return SessionMemoryView.builder()
                .sessionId(rawView.getSessionId())
                .currentRunId(rawView.getCurrentRunId())
                .sessionHistory(rawView.getSessionHistory())
                .retainedRunHistories(retained)
                .historicalIndexes(indexes)
                .injectionText(injectionText)
                .estimatedTokens(injectionText.length())
                .build();
    }

    private SessionMemoryView emptyResult(SessionMemoryView rawView) {
        return SessionMemoryView.builder()
                .sessionId(rawView == null ? null : rawView.getSessionId())
                .currentRunId(rawView == null ? null : rawView.getCurrentRunId())
                .sessionHistory(rawView == null ? null : rawView.getSessionHistory())
                .injectionText("")
                .estimatedTokens(0)
                .build();
    }

    private String renderLatestRunWithinBudget(SessionHistoryMemory.RunHistory run, int availableChars) {
        int capacity = Math.max(0, availableChars);
        int cycles = run.getCycles().size();
        for (int omitted = 0; omitted <= cycles; omitted++) {
            String candidate = renderer.renderRun(run,
                    SessionHistoryMemoryRenderer.RenderOptions.compact(omitted, 700, 500, 2400));
            if (blockLength(candidate) <= capacity) {
                return candidate;
            }
        }
        for (int omitted = 0; omitted <= cycles; omitted++) {
            String candidate = renderer.renderRun(run,
                    SessionHistoryMemoryRenderer.RenderOptions.compact(omitted, 260, 180, 1000));
            if (blockLength(candidate) <= capacity) {
                return candidate;
            }
        }
        String candidate = renderer.renderRun(run,
                SessionHistoryMemoryRenderer.RenderOptions.compact(cycles, 0, 100, 360));
        if (blockLength(candidate) <= capacity) {
            return candidate;
        }
        return renderer.renderCriticalRun(run, capacity);
    }

    private List<SessionHistoryMemory.HistoricalIndexItem> buildIndexes(
            List<SessionHistoryMemory.RunHistory> discarded,
            List<SessionHistoryMemory.RunHistory> retained) {
        if (discarded.isEmpty() || maxIndexes == 0) {
            return List.of();
        }
        Set<String> knownArtifacts = new LinkedHashSet<>();
        Set<String> knownFailures = new LinkedHashSet<>();
        retained.forEach(run -> collectKnownReferences(run, knownArtifacts, knownFailures));

        List<SessionHistoryMemory.HistoricalIndexItem> indexes = new ArrayList<>();
        for (SessionHistoryMemory.RunHistory run : discarded) {
            addFailure(indexes, knownFailures, run.getRunId(), "run:" + safe(run.getRunId()),
                    firstNonBlank(run.getErrorCode(), run.getErrorMessage()));
            for (SessionHistoryMemory.CycleHistory cycle : run.getCycles()) {
                for (SessionHistoryMemory.ToolHistory tool : cycle.getTools()) {
                    addToolIndexes(indexes, knownArtifacts, knownFailures, run.getRunId(), tool);
                }
            }
            for (SessionHistoryMemory.ToolHistory tool : run.getOrphanTools()) {
                addToolIndexes(indexes, knownArtifacts, knownFailures, run.getRunId(), tool);
            }
            for (SessionHistoryMemory.ArtifactReference artifact : run.getUnassociatedArtifacts()) {
                addArtifactIndex(indexes, knownArtifacts, run.getRunId(), "run:" + safe(run.getRunId()), artifact);
            }
            if (indexes.size() >= maxIndexes) {
                break;
            }
        }
        return indexes.size() <= maxIndexes ? indexes : indexes.subList(0, maxIndexes);
    }

    private void collectKnownReferences(SessionHistoryMemory.RunHistory run, Set<String> artifacts, Set<String> failures) {
        if (StringUtils.hasText(firstNonBlank(run.getErrorCode(), run.getErrorMessage()))) {
            failures.add("run:" + safe(run.getRunId()));
        }
        for (SessionHistoryMemory.CycleHistory cycle : run.getCycles()) {
            for (SessionHistoryMemory.ToolHistory tool : cycle.getTools()) {
                collectKnownTool(tool, artifacts, failures);
            }
        }
        run.getOrphanTools().forEach(tool -> collectKnownTool(tool, artifacts, failures));
        run.getUnassociatedArtifacts().forEach(artifact -> artifacts.add(safe(artifact.getArtifactId())));
    }

    private void collectKnownTool(SessionHistoryMemory.ToolHistory tool, Set<String> artifacts, Set<String> failures) {
        tool.getArtifacts().forEach(artifact -> artifacts.add(safe(artifact.getArtifactId())));
        if (StringUtils.hasText(firstNonBlank(tool.getErrorCode(), tool.getErrorMessage())) || failed(tool.getStatus())) {
            failures.add("tool:" + safe(tool.getToolInvocationId()));
        }
    }

    private void addToolIndexes(List<SessionHistoryMemory.HistoricalIndexItem> indexes, Set<String> knownArtifacts,
                                Set<String> knownFailures, String runId, SessionHistoryMemory.ToolHistory tool) {
        for (SessionHistoryMemory.ArtifactReference artifact : tool.getArtifacts()) {
            addArtifactIndex(indexes, knownArtifacts, runId, "tool:" + safe(tool.getToolInvocationId()), artifact);
        }
        if (failed(tool.getStatus()) || StringUtils.hasText(firstNonBlank(tool.getErrorCode(), tool.getErrorMessage()))) {
            addFailure(indexes, knownFailures, runId, "tool:" + safe(tool.getToolInvocationId()),
                    safe(tool.getToolName()) + " 失败：" + firstNonBlank(tool.getErrorCode(), tool.getErrorMessage()));
        }
    }

    private void addArtifactIndex(List<SessionHistoryMemory.HistoricalIndexItem> indexes, Set<String> knownArtifacts,
                                  String runId, String sourceId, SessionHistoryMemory.ArtifactReference artifact) {
        if (indexes.size() >= maxIndexes || artifact == null || !StringUtils.hasText(artifact.getArtifactId())
                || !knownArtifacts.add(artifact.getArtifactId())) {
            return;
        }
        indexes.add(SessionHistoryMemory.HistoricalIndexItem.builder()
                .indexType("可复用产物")
                .sourceRunId(runId)
                .sourceId(sourceId)
                .artifact(artifact)
                .build());
    }

    private void addFailure(List<SessionHistoryMemory.HistoricalIndexItem> indexes, Set<String> knownFailures,
                            String runId, String sourceId, String error) {
        if (indexes.size() >= maxIndexes || !StringUtils.hasText(error) || !knownFailures.add(sourceId)) {
            return;
        }
        indexes.add(SessionHistoryMemory.HistoricalIndexItem.builder()
                .indexType("失败经验")
                .sourceRunId(runId)
                .sourceId(sourceId)
                .summary(error)
                .build());
    }

    private String renderPrompt(String historyText, String indexText) {
        if (!StringUtils.hasText(historyText) && !StringUtils.hasText(indexText)) {
            return "";
        }
        StringBuilder prompt = new StringBuilder(HISTORY_HEADER);
        if (StringUtils.hasText(historyText)) {
            prompt.append(historyText.trim());
        }
        if (StringUtils.hasText(indexText)) {
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append(indexText.trim());
        }
        return prompt.toString().trim();
    }

    private String joinBlocks(List<String> blocks) {
        return blocks.stream().filter(StringUtils::hasText).reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

    private int blockLength(String block) {
        return StringUtils.hasText(block) ? block.length() + 2 : 0;
    }

    private boolean failed(String status) {
        return "FAILED".equalsIgnoreCase(status) || "FAILURE".equalsIgnoreCase(status);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value.trim() : "未知";
    }
}
