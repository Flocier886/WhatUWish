package com.flocier.domain.agent.runtime.memory;

import com.flocier.domain.agent.model.entity.LedgerMemoryBuildCommandEntity;
import com.flocier.domain.agent.model.entity.SessionMemoryView;
import com.flocier.domain.agent.model.entity.ShortTermMemoryAppendCommandEntity;
import com.flocier.domain.agent.service.IMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;


/**
 * 记忆门面。
 * Ledger 连续历史的注入门面。
 * Redis 短期记忆仍可由旧能力保存，但不能进入 AgentContext.memoryPrompt。
 */
@Slf4j
@Component
public class MemoryFacade {

    private final IMemoryService memoryService;
    private final LedgerMemoryExtractor ledgerMemoryExtractor;
    private final MemoryInjectionBudgetPolicy memoryInjectionBudgetPolicy;

    @Autowired
    public MemoryFacade(IMemoryService memoryService,
                        LedgerMemoryExtractor ledgerMemoryExtractor,
                        MemoryInjectionBudgetPolicy memoryInjectionBudgetPolicy) {
        this.memoryService = memoryService;
        this.ledgerMemoryExtractor = ledgerMemoryExtractor;
        this.memoryInjectionBudgetPolicy = memoryInjectionBudgetPolicy;
    }

    /**
     * 兼容旧测试和手动构造场景。
     * 当未提供 Ledger Memory 依赖时，只启用短期记忆逻辑。
     */
    public MemoryFacade(IMemoryService memoryService) {
        this(memoryService, null, null);
    }

    /**
     * @deprecated Ledger history 的读取窗口由 memory.ledger.max-history-runs 和字符预算统一控制，
     * recentRounds 不再参与正式 memory 注入。请使用不带 recentRounds 的重载。
     */
    @Deprecated
    public String buildPromptWithMemory(String originalTask, String sessionId, int recentRounds) {
        return buildPromptWithMemory(originalTask, sessionId, null, null);
    }

    /**
     * 构建最终要注入模型的记忆提示。
     * memoryPrompt 只来自 Ledger 连续历史，避免早期 Redis 问答污染 Agent 决策上下文。
     */
    public String buildPromptWithMemory(String originalTask,
                                        String sessionId,
                                        String currentRunId,
                                        String currentRequestId) {
        String mergedPrompt = buildMemoryPrompt(sessionId, currentRunId, currentRequestId);
        if (!StringUtils.hasText(mergedPrompt)) {
            return originalTask;
        }
        return originalTask + "\n\n" + mergedPrompt;
    }

    /**
     * @deprecated Ledger history 的读取窗口由 memory.ledger.max-history-runs 和字符预算统一控制，
     * recentRounds 不再参与正式 memory 注入。请使用不带 recentRounds 的重载。
     */
    @Deprecated
    public String buildPromptWithMemory(String originalTask,
                                        String sessionId,
                                        String currentRunId,
                                        String currentRequestId,
                                        int recentRounds) {
        return buildPromptWithMemory(originalTask, sessionId, currentRunId, currentRequestId);
    }

    /**
     * 仅构建记忆注入文本，不拼接原始任务。
     * 用于主链路中将 task 与 memoryPrompt 分离，避免后续节点误把记忆当成用户原始输入反复传播。
     */
    public String buildMemoryPrompt(String sessionId,
                                    String currentRunId,
                                    String currentRequestId) {
        if (!StringUtils.hasText(sessionId)) {
            return "";
        }
        return loadLedgerPrompt(sessionId, currentRunId, currentRequestId);
    }

    /**
     * @deprecated Ledger history 的读取窗口由 memory.ledger.max-history-runs 和字符预算统一控制，
     * recentRounds 不再参与正式 memory 注入。请使用不带 recentRounds 的重载。
     */
    @Deprecated
    public String buildMemoryPrompt(String sessionId,
                                    String currentRunId,
                                    String currentRequestId,
                                    int recentRounds) {
        return buildMemoryPrompt(sessionId, currentRunId, currentRequestId);
    }

    public void appendMemory(String sessionId, String userQuestion, String finalAnswer, int windowSize) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userQuestion) || !StringUtils.hasText(finalAnswer)) {
            return;
        }
        try {
            memoryService.appendShortTermMemory(ShortTermMemoryAppendCommandEntity.builder()
                    .sessionId(sessionId)
                    .userQuestion(userQuestion)
                    .finalAnswer(finalAnswer)
                    .windowSize(windowSize)
                    .build());
        } catch (Exception e) {
            log.warn("append memory failed. sessionId:{} err:{}", sessionId, e.getMessage());
        }
    }

    private String loadLedgerPrompt(String sessionId, String currentRunId, String currentRequestId) {
        if (ledgerMemoryExtractor == null || memoryInjectionBudgetPolicy == null) {
            return "";
        }
        try {
            SessionMemoryView rawView = ledgerMemoryExtractor.extract(LedgerMemoryBuildCommandEntity.builder()
                    .sessionId(sessionId)
                    .currentRunId(currentRunId)
                    .currentRequestId(currentRequestId)
                    .build());
            SessionMemoryView budgetedView = memoryInjectionBudgetPolicy.apply(rawView);
            return budgetedView == null ? "" : budgetedView.getInjectionText();
        } catch (Exception e) {
            log.warn("load ledger memory failed, continue without it. sessionId:{} err:{}", sessionId, e.getMessage());
            return "";
        }
    }

}
