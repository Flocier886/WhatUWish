package com.flocier.domain.agent.service.execute.fixed;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.model.vo.AiAgentClientFlowConfigVO;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.client.AiClientRuntimeRegistry;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.ledger.service.LedgerFactService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service("fixedAgentExecuteStrategy")
public class FixedAgentExecuteStrategy {

    private static final int FIXED_MEMORY_WINDOW_SIZE = 12;

    @Resource
    private IAgentRepository repository;
    @Resource
    private AiClientRuntimeRegistry aiClientRuntimeRegistry;
    @Resource
    private LLM llm;
    @Resource
    private MemoryFacade memoryFacade;
    @Resource
    private LedgerFactService ledgerFactService;

    /**
     * 固定链路执行入口，兼容仅传 SSE emitter 的旧调用方式。
     */
    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        execute(requestParameter, emitter, null);
    }

    /**
     * 固定链路执行入口。
     * task 只保留原始用户请求，memoryPrompt 独立保存账本/短期记忆注入内容，避免 prompt 污染。
     */
    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter, AgentContext agentContext) throws Exception {
        prepareLedgerContext(requestParameter, agentContext);
        ledgerFactService.recordRunStarted(agentContext, requestParameter.getAiAgentId(), "fixed");

        String content = null;
        try {
            List<AiAgentClientFlowConfigVO> clientFlowConfigList = repository.queryAiClientsByAgentId(requestParameter.getAiAgentId());
            String memoryPrompt = memoryFacade.buildMemoryPrompt(
                    requestParameter.getSessionId(),
                    agentContext == null ? null : (String) agentContext.getAttribute("runId"),
                    agentContext == null ? null : agentContext.getRequestId()
            );
            String effectiveUserContent = composeEffectiveUserContent(requestParameter.getMessage(), memoryPrompt);
            if (agentContext != null) {
                agentContext.setMemoryPrompt(memoryPrompt);
            }

            log.info("fixed execute start, agentId={}, sessionId={}, runId={}, requestId={}, traceId={}, clientCount={}, memoryPromptLength={}",
                    requestParameter.getAiAgentId(),
                    requestParameter.getSessionId(),
                    agentContext == null ? null : agentContext.getAttribute("runId"),
                    agentContext == null ? null : agentContext.getRequestId(),
                    agentContext == null ? null : agentContext.getTraceId(),
                    clientFlowConfigList == null ? 0 : clientFlowConfigList.size(),
                    memoryPrompt == null ? 0 : memoryPrompt.length());

            for (AiAgentClientFlowConfigVO flowConfigVO : clientFlowConfigList) {
                aiClientRuntimeRegistry.getRequiredChatClient(flowConfigVO.getClientId());
                content = llm.callText(LlmRequest.builder()
                        .model(flowConfigVO.getClientId())
                        .messages(List.of(
                                LlmMessage.builder()
                                        .role(LlmMessageRole.SYSTEM)
                                        .content("current_date=" + LocalDate.now())
                                        .build(),
                                LlmMessage.builder()
                                        .role(LlmMessageRole.USER)
                                        .content(effectiveUserContent)
                                        .build()
                        ))
                        .attributes(buildLedgerAttributes(requestParameter, agentContext))
                        .build());
                log.info("fixed agent executing, agentId={}, clientId={}, runId={}, requestId={}",
                        requestParameter.getAiAgentId(),
                        flowConfigVO.getClientId(),
                        agentContext == null ? null : agentContext.getAttribute("runId"),
                        agentContext == null ? null : agentContext.getRequestId());
            }

            if (StringUtils.hasText(content)) {
                memoryFacade.appendMemory(
                        requestParameter.getSessionId(),
                        requestParameter.getMessage(),
                        content,
                        FIXED_MEMORY_WINDOW_SIZE
                );
                writeResult(agentContext, emitter, AutoAgentExecuteResultEntity.createSummaryResult(content, requestParameter.getSessionId()));
            }

            writeResult(agentContext, emitter, AutoAgentExecuteResultEntity.createCompleteResult(requestParameter.getSessionId()));
            ledgerFactService.recordRunFinished(agentContext, "COMPLETED", content, null, null);
            log.info("fixed execute finished, agentId={}, sessionId={}, runId={}, requestId={}, resultPreview={}",
                    requestParameter.getAiAgentId(),
                    requestParameter.getSessionId(),
                    agentContext == null ? null : agentContext.getAttribute("runId"),
                    agentContext == null ? null : agentContext.getRequestId(),
                    preview(content));
        } catch (Exception e) {
            ledgerFactService.recordRunFinished(agentContext, "FAILED", content, "fixed_execute_failed", e.getMessage());
            throw e;
        }
    }

    private void writeResult(AgentContext agentContext, ResponseBodyEmitter emitter, AutoAgentExecuteResultEntity result) {
        if (agentContext != null && agentContext.getResultWriter() != null) {
            agentContext.getResultWriter().write(result);
            return;
        }
        sendEmitterResult(emitter, result);
    }

    private void sendEmitterResult(ResponseBodyEmitter emitter, AutoAgentExecuteResultEntity result) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send("data: " + JSON.toJSONString(result) + "\n\n");
        } catch (Exception e) {
            log.error("send result failed", e);
        }
    }

    /**
     * 构建固定链路的账本属性，用于关联 run、request、session、trace 等追踪维度。
     */
    private Map<String, Object> buildLedgerAttributes(ExecuteCommandEntity requestParameter, AgentContext agentContext) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("scene", "fixed_execute");
        attributes.put("agentId", requestParameter.getAiAgentId());
        attributes.put("sessionId", requestParameter.getSessionId());
        if (agentContext != null) {
            Object runId = agentContext.getAttribute("runId");
            if (runId != null) {
                attributes.put("runId", runId);
            }
            if (agentContext.getRequestId() != null) {
                attributes.put("requestId", agentContext.getRequestId());
            }
            if (agentContext.getTraceId() != null) {
                attributes.put("traceId", agentContext.getTraceId());
            }
        }
        return attributes;
    }

    /**
     * 将原始任务和记忆提示在调用模型前进行一次性拼接，避免记忆内容被写回 task 主字段。
     */
    private String composeEffectiveUserContent(String originalTask, String memoryPrompt) {
        if (!StringUtils.hasText(memoryPrompt)) {
            return originalTask;
        }
        if (!StringUtils.hasText(originalTask)) {
            return memoryPrompt;
        }
        return originalTask + "\n\n" + memoryPrompt.trim();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    /**
     * 准备固定链路执行所需的账本上下文，并初始化 memoryPrompt 占位。
     */
    private void prepareLedgerContext(ExecuteCommandEntity requestParameter, AgentContext agentContext) {
        if (agentContext == null) {
            return;
        }
        agentContext.setSessionId(requestParameter.getSessionId());
        if (agentContext.getRequestId() == null || agentContext.getRequestId().isBlank()) {
            agentContext.setRequestId(UUID.randomUUID().toString());
        }
        if (agentContext.getTraceId() == null || agentContext.getTraceId().isBlank()) {
            agentContext.setTraceId(agentContext.getRequestId());
        }
        if (agentContext.getAttribute("runId") == null) {
            agentContext.setAttribute("runId", UUID.randomUUID().toString());
        }
        agentContext.setAttribute("agentId", requestParameter.getAiAgentId());
        agentContext.setQuery(requestParameter.getMessage());
        agentContext.setTask(requestParameter.getMessage());
        agentContext.setMemoryPrompt("");
    }
}
