package com.flocier.domain.agent.runtime.memory;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 主 ReAct 运行中压缩服务：模型决策前估算预算，压缩成功后即时替换持久化投影。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionContextCompactionService {
    private static final int MAX_AUDIT_CHARS = 500_000;
    /** 发给摘要模型的指令；英文标题是模型输出格式，不是代码注释。 */
    private static final String COMPACT_PROMPT = """
            Create a structured checkpoint of earlier conversation turns. Respond with text only; do not call tools.
            Use only evidenced facts. Preserve the user's latest unfinished request, goal, constraints, completed
            actions, current progress, unresolved issues, decisions and relevant files. When a previous checkpoint
            is provided, update it with new turns and remove stale active or blocked items. Do not invent events,
            retain passwords, API keys or tokens, or give instructions to the continuing assistant.
            Use these Markdown headings in order: Historical Task Snapshot, Goal, Constraints & Preferences,
            Completed Actions, Active State, Blocked, Key Decisions, Resolved Questions, Relevant Files,
            Critical Context.
            """;

    private final WorkingMemoryCompactor compactor;
    private final SessionWorkingMemoryRepository repository;
    private final LLM llm;
    private final ReactorConfig config;

    /** 工作记忆总开关和压缩预算均由应用配置控制。 */
    @Value("${memory.working.enabled:true}") private boolean enabled;
    @Value("${memory.working.compaction.enabled:true}") private boolean compactionEnabled;
    @Value("${memory.working.context-window-tokens:100000}") private int contextWindow;
    @Value("${memory.working.compaction.threshold-percent:0.50}") private double thresholdPercent;
    @Value("${memory.working.compaction.keep-min-tokens:10000}") private int keepMinTokens;
    @Value("${memory.working.compaction.keep-min-text-messages:5}") private int keepMinTextMessages;
    @Value("${memory.working.compaction.micro-keep-recent-tool-results:5}") private int keepRecentTools;
    @Value("${memory.working.compaction.micro-tool-result-max-chars:8000}") private int toolResultMaxChars;
    @Value("${memory.working.compaction.summary-input-max-chars:160000}") private int summaryInputMaxChars;
    @Value("${memory.working.compaction.summarizer-timeout-seconds:120}") private int summarizerTimeoutSeconds;

    /** 先做旧工具结果微压缩；仍超阈值时生成摘要，并只在投影写入成功后返回新上下文。 */
    public List<LlmMessage> applyIfNeeded(String sessionId, String requestId, String systemPrompt,
                                          String toolDescription, List<LlmMessage> messages) {
        if (!enabled || !compactionEnabled || messages == null || messages.isEmpty()) return messages;
        int threshold = threshold();
        int fixedTokens = compactor.estimateText(systemPrompt) + compactor.estimateText(toolDescription);
        int before = fixedTokens + compactor.estimateTokens(messages);
        List<LlmMessage> micro = compactor.microcompact(messages, keepRecentTools, toolResultMaxChars);
        int microTokens = fixedTokens + compactor.estimateTokens(micro);
        if (microTokens < threshold) {
            if (!micro.equals(messages)) return persist(sessionId, requestId, messages, micro, "micro-only", before, threshold, null, null);
            return messages;
        }

        WorkingMemoryCompactor.Window window = compactor.split(micro, keepMinTokens, keepMinTextMessages);
        if (window == null) {
            if (!micro.equals(messages)) return persist(sessionId, requestId, messages, micro, "micro-only", before, threshold, null, null);
            return messages;
        }
        String previous = !micro.isEmpty() && compactor.isSummary(micro.get(0)) ? micro.get(0).getContent() : null;
        String summary;
        String strategy = "full-llm";
        try {
            summary = summarize(window.middle(), previous, sessionId, requestId);
            if (summary == null || summary.isBlank()) throw new IllegalStateException("empty compaction summary");
        } catch (Exception error) {
            if (isPermanent(error)) {
                record(sessionId, requestId, null, messages, messages, "abort-unchanged", 2,
                        before, threshold, null, error.getMessage());
                return messages;
            }
            summary = localSummary(window.middle(), previous);
            strategy = "local-fallback";
        }
        List<LlmMessage> compacted = compactor.buildCompacted(window, summary);
        if (fixedTokens + compactor.estimateTokens(compacted) >= threshold) {
            compacted = compactor.dropOldestToFit(compacted, threshold - fixedTokens);
            strategy += "+drop-oldest";
        }
        return persist(sessionId, requestId, messages, compacted, strategy, before, threshold, summary, null);
    }

    /** 用现有 LLM 入口限时生成摘要；已有摘要与本次中段一起迭代更新。 */
    private String summarize(List<LlmMessage> middle, String previous, String sessionId, String requestId) throws Exception {
        String model = config.getSummaryModelName();
        if (model == null || model.isBlank()) model = config.getReactModelName();
        String payload = (previous == null ? "" : "PREVIOUS SUMMARY:\n" + previous + "\n\n")
                + "NEW TURNS:\n" + compactor.serialize(middle, summaryInputMaxChars);
        LlmRequest request = LlmRequest.builder().model(model).temperature(0.2)
                .messages(List.of(LlmMessage.builder().role(LlmMessageRole.SYSTEM).content(COMPACT_PROMPT).build(),
                        LlmMessage.builder().role(LlmMessageRole.USER).content(payload).build()))
                .attributes(java.util.Map.of("scene", "working_memory_compaction", "sessionId", sessionId,
                        "requestId", requestId)).build();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> llm.callText(request));
        try {
            return future.get(Math.max(1, summarizerTimeoutSeconds), TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            throw e;
        }
    }

    /** 摘要服务暂时失败时的限长本地降级文本。 */
    private String localSummary(List<LlmMessage> middle, String previous) {
        String serialized = compactor.serialize(middle, 6000);
        return (previous == null ? "" : previous + "\n") + "Earlier conversation excerpt:\n" + serialized;
    }

    /** 数据库替换失败时返回原消息，防止本轮后续增量与库中 READY 前缀断开。 */
    private List<LlmMessage> persist(String sessionId, String requestId, List<LlmMessage> beforeMessages,
                                     List<LlmMessage> afterMessages, String strategy, int beforeTokens,
                                     int threshold, String summary, String error) {
        if (afterMessages.equals(beforeMessages)) return beforeMessages;
        String compactId = "wm-compact-" + UUID.randomUUID().toString().replace("-", "");
        try {
            if (!repository.replaceReadyProjection(sessionId, compactId, afterMessages)) compactId = null;
        } catch (Exception e) {
            log.warn("persist compacted projection failed, sessionId={}", sessionId, e);
            compactId = null;
            error = e.getMessage();
        }
        record(sessionId, requestId, compactId, beforeMessages, afterMessages, strategy,
                compactId == null ? 2 : 1, beforeTokens, threshold, summary, error);
        return compactId == null ? beforeMessages : afterMessages;
    }

    /** 审计记录与模型工作记忆分离；记录失败只告警，不覆盖 ReAct 主流程结果。 */
    private void record(String sessionId, String requestId, String compactId, List<LlmMessage> before,
                        List<LlmMessage> after, String strategy, int status, int beforeTokens,
                        int threshold, String summary, String error) {
        WorkingMemoryCompactionEvent event = new WorkingMemoryCompactionEvent();
        event.setSessionId(sessionId);
        event.setTriggerRequestId(requestId);
        event.setCompactRequestId(compactId);
        event.setStrategy(strategy);
        event.setStatus(status);
        event.setBeforeTokens(beforeTokens);
        event.setAfterTokens(compactor.estimateTokens(after));
        event.setBeforeMessageCount(before.size());
        event.setAfterMessageCount(after.size());
        event.setThresholdTokens(threshold);
        event.setSummaryText(summary);
        event.setBeforeMessagesJson(limit(JSON.toJSONString(before)));
        event.setAfterMessagesJson(limit(JSON.toJSONString(after)));
        event.setErrorMessage(error == null ? null : error.substring(0, Math.min(1024, error.length())));
        try {
            repository.recordCompaction(event);
        } catch (Exception e) {
            log.warn("record working memory compaction failed, sessionId={}", sessionId, e);
        }
    }

    private String limit(String text) {
        return text == null || text.length() <= MAX_AUDIT_CHARS ? text : text.substring(0, MAX_AUDIT_CHARS);
    }

    /** 认证、权限和配额错误不做本地摘要降级，保留原消息等待配置修复。 */
    private boolean isPermanent(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String text = String.valueOf(cause.getMessage()).toLowerCase();
            if (text.contains("401") || text.contains("403") || text.contains("quota")
                    || text.contains("billing") || text.contains("unauthorized")) return true;
        }
        return false;
    }

    /** 与 master 一致地限制小窗口触发比例，并避免阈值达到或超过窗口。 */
    private int threshold() {
        int window = Math.max(2, contextWindow);
        double percent = window < 512_000 ? Math.max(thresholdPercent, 0.75) : thresholdPercent;
        int floored = Math.max((int) (window * percent), 64_000);
        return floored >= window ? Math.max(1, Math.min((int) (window * 0.85), window - 1)) : floored;
    }
}
