package com.flocier.domain.agent.runtime.memory;

import com.flocier.domain.agent.model.entity.SessionHistoryMemory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;

/** 将连续历史投影渲染为 LLM 可见文本，不读取或修改 Ledger。 */
@Component
public class SessionHistoryMemoryRenderer {

    public String renderRun(SessionHistoryMemory.RunHistory run, RenderOptions options) {
        StringBuilder builder = new StringBuilder();
        builder.append("### Run ").append(value(run.getRunId()));
        appendInline(builder, "状态", run.getStatus());
        builder.append('\n');
        appendLine(builder, "结论", compact(run.getSummary(), options.summaryChars()));
        appendLine(builder, "失败", compact(firstNonBlank(run.getErrorCode(), run.getErrorMessage()), options.errorChars()));

        List<SessionHistoryMemory.CycleHistory> cycles = run.getCycles();
        for (int i = 0; i < cycles.size(); i++) {
            SessionHistoryMemory.CycleHistory cycle = cycles.get(i);
            builder.append("[ReAct Cycle ").append(i + 1).append("]\n");
            if (i < options.omitLlmBeforeCycle()) {
                builder.append("LLM： [普通响应已按预算省略]\n");
            } else {
                appendLine(builder, "LLM", compact(cycle.getResponseText(), options.llmChars()));
            }
            for (SessionHistoryMemory.ToolHistory tool : cycle.getTools()) {
                appendTool(builder, tool, options);
            }
        }
        for (SessionHistoryMemory.ToolHistory orphan : run.getOrphanTools()) {
            builder.append("[未关联 LLM Cycle 的工具事实]\n");
            appendTool(builder, orphan, options);
        }
        for (SessionHistoryMemory.ArtifactReference artifact : run.getUnassociatedArtifacts()) {
            appendArtifact(builder, artifact, options.artifactSummaryChars());
        }
        return builder.toString().trim();
    }

    public String renderIndexes(List<SessionHistoryMemory.HistoricalIndexItem> indexes, int maxChars) {
        if (indexes == null || indexes.isEmpty() || maxChars <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## 较早历史索引（完整 Run 已因预算省略）\n");
        for (SessionHistoryMemory.HistoricalIndexItem item : indexes) {
            String line = renderIndex(item);
            if (builder.length() + line.length() + 1 > maxChars) {
                break;
            }
            builder.append(line).append('\n');
        }
        return builder.toString().trim();
    }

    /**
     * 最终预算降级视图：舍弃普通 LLM 文本和工具参数，但仍保留下一步决策价值最高的 observation、失败与产物引用。
     */
    public String renderCriticalRun(SessionHistoryMemory.RunHistory run, int maxChars) {
        StringBuilder builder = new StringBuilder();
        builder.append("### Run ").append(value(run.getRunId())).append("\n[部分普通历史已按预算省略]\n");
        appendLine(builder, "失败", compact(firstNonBlank(run.getErrorCode(), run.getErrorMessage()), 180));
        for (SessionHistoryMemory.CycleHistory cycle : run.getCycles()) {
            for (SessionHistoryMemory.ToolHistory tool : cycle.getTools()) {
                appendCriticalTool(builder, tool, maxChars);
            }
        }
        for (SessionHistoryMemory.ToolHistory orphan : run.getOrphanTools()) {
            appendCriticalTool(builder, orphan, maxChars);
        }
        for (SessionHistoryMemory.ArtifactReference artifact : run.getUnassociatedArtifacts()) {
            appendCriticalArtifact(builder, artifact, maxChars);
        }
        return builder.toString().trim();
    }

    private String renderIndex(SessionHistoryMemory.HistoricalIndexItem item) {
        StringBuilder line = new StringBuilder("- [").append(value(item.getIndexType())).append("] ");
        if (item.getArtifact() != null) {
            SessionHistoryMemory.ArtifactReference artifact = item.getArtifact();
            line.append("artifactId=").append(value(artifact.getArtifactId()))
                    .append("，名称=").append(value(artifact.getName()));
            appendInline(line, "用途", compact(artifact.getUsageSummary(), 220));
            appendInline(line, "读取引用", artifact.getStorageRef());
        } else {
            line.append(compact(item.getSummary(), 300));
        }
        appendInline(line, "来源 run", item.getSourceRunId());
        appendInline(line, "来源", item.getSourceId());
        return line.toString();
    }

    private void appendTool(StringBuilder builder, SessionHistoryMemory.ToolHistory tool, RenderOptions options) {
        builder.append("工具：").append(value(tool.getToolName()));
        appendInline(builder, "状态", tool.getStatus());
        appendInline(builder, "关联", tool.getAssociation());
        appendInline(builder, "调用", tool.getToolInvocationId());
        builder.append('\n');
        appendLine(builder, "  参数", compact(tool.getArguments(), options.argumentsChars()));
        appendLine(builder, "  observation", compact(tool.getObservation(), options.observationChars()));
        appendLine(builder, "  错误", compact(firstNonBlank(tool.getErrorCode(), tool.getErrorMessage()), options.errorChars()));
        for (SessionHistoryMemory.ArtifactReference artifact : tool.getArtifacts()) {
            appendArtifact(builder, artifact, options.artifactSummaryChars());
        }
    }

    private void appendCriticalTool(StringBuilder builder, SessionHistoryMemory.ToolHistory tool, int maxChars) {
        if (builder.length() >= maxChars) {
            return;
        }
        StringBuilder candidate = new StringBuilder();
        candidate.append("工具：").append(value(tool.getToolName())).append('\n');
        appendLine(candidate, "  observation", compact(tool.getObservation(), 260));
        appendLine(candidate, "  错误", compact(firstNonBlank(tool.getErrorCode(), tool.getErrorMessage()), 180));
        for (SessionHistoryMemory.ArtifactReference artifact : tool.getArtifacts()) {
            StringBuilder artifactCandidate = new StringBuilder(candidate);
            appendArtifact(artifactCandidate, artifact, 120);
            if (builder.length() + artifactCandidate.length() > maxChars) {
                break;
            }
            candidate = artifactCandidate;
        }
        if (builder.length() + candidate.length() <= maxChars) {
            builder.append(candidate);
        }
    }

    private void appendCriticalArtifact(StringBuilder builder, SessionHistoryMemory.ArtifactReference artifact, int maxChars) {
        StringBuilder candidate = new StringBuilder();
        appendArtifact(candidate, artifact, 120);
        if (builder.length() + candidate.length() <= maxChars) {
            builder.append(candidate);
        }
    }

    private void appendArtifact(StringBuilder builder, SessionHistoryMemory.ArtifactReference artifact, int summaryChars) {
        builder.append("  artifact：artifactId=").append(value(artifact.getArtifactId()))
                .append("，名称=").append(value(artifact.getName()));
        appendInline(builder, "类型", artifact.getType());
        appendInline(builder, "用途", compact(artifact.getUsageSummary(), summaryChars));
        appendInline(builder, "读取引用", artifact.getStorageRef());
        builder.append("；需内容时通过产物读取能力按需读取\n");
    }

    private void appendLine(StringBuilder builder, String label, String text) {
        if (StringUtils.hasText(text)) {
            builder.append(label).append("：").append(text).append('\n');
        }
    }

    private void appendInline(StringBuilder builder, String label, String text) {
        if (StringUtils.hasText(text)) {
            builder.append("，").append(label).append('=').append(text.trim());
        }
    }

    /**
     * 只在句子、词或完整行边界上压缩投影文本。无法在边界内保留任何内容时直接声明省略，
     * 不对原始事实做裸 substring 截断。
     */
    private String compact(String text, int maxChars) {
        if (!StringUtils.hasText(text) || maxChars <= 0) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        String sentence = takeAtBoundary(normalized, maxChars, BreakIterator.getSentenceInstance(Locale.CHINA));
        if (StringUtils.hasText(sentence)) {
            return sentence + " [后续内容已按预算省略]";
        }
        String words = takeAtBoundary(normalized, maxChars, BreakIterator.getWordInstance(Locale.CHINA));
        return StringUtils.hasText(words) ? words + " [后续内容已按预算省略]" : "[该字段内容过长，已按预算省略]";
    }

    private String takeAtBoundary(String text, int maxChars, BreakIterator iterator) {
        iterator.setText(text);
        StringBuilder builder = new StringBuilder();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String part = text.substring(start, end);
            if (builder.length() + part.length() > maxChars) {
                break;
            }
            builder.append(part);
        }
        return builder.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String value(String text) {
        return StringUtils.hasText(text) ? text.trim() : "未知";
    }

    public record RenderOptions(int summaryChars, int llmChars, int argumentsChars, int observationChars,
                                int errorChars, int artifactSummaryChars, int omitLlmBeforeCycle) {
        public static RenderOptions full() {
            return new RenderOptions(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        }

        public static RenderOptions compact(int omitLlmBeforeCycle, int llmChars, int argumentsChars,
                                            int observationChars) {
            return new RenderOptions(900, llmChars, argumentsChars, observationChars, 500, 360,
                    omitLlmBeforeCycle);
        }
    }
}
