package com.flocier.domain.agent.service.execute.auto;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import com.flocier.domain.ledger.service.LedgerFactService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.UUID;

/**
 * 工作流域内部的自主执行模板。
 * 该类不再作为对外执行策略入口，只用于 workflow 应用编排层内部路由调用。
 */
@Service("autoAgentExecuteStrategy")
@Slf4j
public class AutoAgentExecuteStrategy {
    private static final int AUTO_MEMORY_WINDOW_SIZE = 12;

    @Resource
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;
    @Resource
    private MemoryFacade memoryFacade;
    @Resource
    private LedgerFactService ledgerFactService;

    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter, AgentContext agentContext) throws Exception {
        prepareLedgerContext(requestParameter, agentContext);
        ledgerFactService.recordRunStarted(agentContext, requestParameter.getAiAgentId(), "auto");
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> executeHandler =
                defaultAutoAgentExecuteStrategyFactory.workflowStrategyHandler();

        String memoryPrompt = memoryFacade.buildMemoryPrompt(
                requestParameter.getSessionId(),
                agentContext == null ? null : (String) agentContext.getAttribute("runId"),
                agentContext == null ? null : agentContext.getRequestId()
        );
        if (agentContext != null) {
            agentContext.setMemoryPrompt(memoryPrompt);
        }

        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(requestParameter.getMaxStep() != null ? requestParameter.getMaxStep() : 3);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(composeEffectiveTask(requestParameter.getMessage(), memoryPrompt));
        dynamicContext.setValue("agentContext", agentContext);
        dynamicContext.setValue("emitter", emitter);

        try {
            String result = executeHandler.apply(requestParameter, dynamicContext);
            appendShortTermMemory(requestParameter, dynamicContext, result);
            ledgerFactService.recordRunFinished(agentContext, "COMPLETED", dynamicContext.getValue("finalSummary"), null, null);

            AutoAgentExecuteResultEntity completeResult =
                    AutoAgentExecuteResultEntity.createCompleteResult(requestParameter.getSessionId());
            writeResult(agentContext, emitter, completeResult);
        } catch (Exception e) {
            ledgerFactService.recordRunFinished(agentContext, "FAILED", null, "auto_execute_failed", e.getMessage());
            throw e;
        }
    }

    private void appendShortTermMemory(ExecuteCommandEntity requestParameter,
                                       DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                       String fallbackAnswer) {
        if (!StringUtils.hasText(requestParameter.getSessionId()) || !StringUtils.hasText(requestParameter.getMessage())) {
            return;
        }
        String finalAnswer = dynamicContext.getValue("finalSummary");
        if (!StringUtils.hasText(finalAnswer)) {
            finalAnswer = fallbackAnswer;
        }
        if (!StringUtils.hasText(finalAnswer)) {
            return;
        }
        memoryFacade.appendMemory(
                requestParameter.getSessionId(),
                requestParameter.getMessage(),
                finalAnswer,
                AUTO_MEMORY_WINDOW_SIZE
        );
    }

    /**
     * auto 链路实际推进的 currentTask 允许附带记忆注入文本，
     * 但只在入口处拼接一次，避免后续节点重复污染。
     */
    private String composeEffectiveTask(String originalTask, String memoryPrompt) {
        if (!StringUtils.hasText(memoryPrompt)) {
            return originalTask;
        }
        if (!StringUtils.hasText(originalTask)) {
            return memoryPrompt;
        }
        return originalTask + "\n\n" + memoryPrompt.trim();
    }

    private void writeResult(AgentContext agentContext, ResponseBodyEmitter emitter, AutoAgentExecuteResultEntity result) {
        if (agentContext != null && agentContext.getResultWriter() != null) {
            agentContext.getResultWriter().write(result);
            return;
        }
        if (emitter == null) {
            return;
        }
        try {
            emitter.send("data: " + JSON.toJSONString(result) + "\n\n");
        } catch (Exception e) {
            log.error("send complete result failed", e);
        }
    }

    /**
     * 准备 auto 链路账本上下文。
     */
    private void prepareLedgerContext(ExecuteCommandEntity commandEntity, AgentContext agentContext) {
        if (agentContext == null) {
            return;
        }
        agentContext.setSessionId(commandEntity.getSessionId());
        if (agentContext.getRequestId() == null || agentContext.getRequestId().isBlank()) {
            agentContext.setRequestId(UUID.randomUUID().toString());
        }
        if (agentContext.getTraceId() == null || agentContext.getTraceId().isBlank()) {
            agentContext.setTraceId(agentContext.getRequestId());
        }
        if (agentContext.getAttribute("runId") == null) {
            agentContext.setAttribute("runId", UUID.randomUUID().toString());
        }
        agentContext.setAttribute("agentId", commandEntity.getAiAgentId());
        agentContext.setQuery(commandEntity.getMessage());
        agentContext.setTask(commandEntity.getMessage());
        agentContext.setMemoryPrompt("");
    }
}
