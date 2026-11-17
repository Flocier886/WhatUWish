package com.flocier.domain.agent.service.execute.flow;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import com.flocier.domain.ledger.service.LedgerFactService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.UUID;

/**
 * 工作流域内部的顺序执行模板。
 * 该类不再作为对外执行策略入口，只用于 workflow 应用编排层内部路由调用。
 */
@Slf4j
@Service("flowAgentExecuteStrategy")
public class FlowExecuteStrategy {
    private static final int FLOW_MEMORY_WINDOW_SIZE = 12;

    @Resource
    private DefaultFlowAgentExecuteStrategyFactory defaultFlowAgentExecuteStrategyFactory;
    @Resource
    private MemoryFacade memoryFacade;
    @Resource
    private LedgerFactService ledgerFactService;

    public void execute(ExecuteCommandEntity executeCommandEntity, ResponseBodyEmitter emitter, AgentContext agentContext) throws Exception {
        prepareLedgerContext(executeCommandEntity, agentContext);
        ledgerFactService.recordRunStarted(agentContext, executeCommandEntity.getAiAgentId(), "workflow");
        StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> executeHandler =
                defaultFlowAgentExecuteStrategyFactory.workflowStrategyHandler();

        String memoryPrompt = memoryFacade.buildMemoryPrompt(
                executeCommandEntity.getSessionId(),
                agentContext == null ? null : (String) agentContext.getAttribute("runId"),
                agentContext == null ? null : agentContext.getRequestId()
        );
        if (agentContext != null) {
            agentContext.setMemoryPrompt(memoryPrompt);
        }

        DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(executeCommandEntity.getMaxStep() != null ? executeCommandEntity.getMaxStep() : 5);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(composeEffectiveTask(executeCommandEntity.getMessage(), memoryPrompt));
        dynamicContext.setValue("agentContext", agentContext);
        dynamicContext.setValue("emitter", emitter);

        try {
            String result = executeHandler.apply(executeCommandEntity, dynamicContext);
            appendShortTermMemory(executeCommandEntity, dynamicContext, result);
            ledgerFactService.recordRunFinished(agentContext, "COMPLETED", dynamicContext.getValue("finalSummary"), null, null);

            AutoAgentExecuteResultEntity completeResult =
                    AutoAgentExecuteResultEntity.createCompleteResult(executeCommandEntity.getSessionId());
            writeResult(agentContext, emitter, completeResult);
        } catch (Exception e) {
            ledgerFactService.recordRunFinished(agentContext, "FAILED", null, "workflow_execute_failed", e.getMessage());
            throw e;
        }
    }

    private void appendShortTermMemory(ExecuteCommandEntity executeCommandEntity,
                                       DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                       String fallbackAnswer) {
        if (!StringUtils.hasText(executeCommandEntity.getSessionId()) || !StringUtils.hasText(executeCommandEntity.getMessage())) {
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
                executeCommandEntity.getSessionId(),
                executeCommandEntity.getMessage(),
                finalAnswer,
                FLOW_MEMORY_WINDOW_SIZE
        );
    }

    /**
     * flow 链路推进用的 currentTask 由原始任务和记忆提示组成，
     * 但不会回写到 AgentContext.task，避免污染用户原始输入语义。
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
     * 准备 workflow 链路账本上下文。
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
