package com.flocier.domain.agent.service.execute.flow.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.model.vo.AiAgentClientFlowConfigVO;
import com.flocier.domain.agent.model.vo.AiClientTypeEnumVO;
import com.flocier.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component("step5FlowSummaryNode")
public class Step5FlowSummaryNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(ExecuteCommandEntity request, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("开始执行第五步：LLM 总结复盘");
        //提前保存兜底总结
        String summaryResult = buildFallbackSummary(request, dynamicContext);
        try {
            AiAgentClientFlowConfigVO summaryClientConfig = dynamicContext.getAiAgentClientFlowConfigVOMap()
                    .get(AiClientTypeEnumVO.FLOW_SUMMARY_CLIENT.getCode());
            if (summaryClientConfig == null) {
                summaryClientConfig = dynamicContext.getAiAgentClientFlowConfigVOMap()
                        .get(AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode());
            }

            if (summaryClientConfig != null) {
                ChatClient summaryChatClient = getChatClientByClientId(summaryClientConfig.getClientId());
                String prompt = buildSummaryPrompt(request, dynamicContext);
                String llmSummary = summaryChatClient.prompt()
                        .user(prompt)
                        .system(s -> s.param("current_date", LocalDate.now().toString()))
                        .advisors(a -> a
                                .param(ChatMemory.CONVERSATION_ID, request.getSessionId() + "-flow-summary")
                                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50))
                        .call()
                        .content();
                if (llmSummary != null && !llmSummary.isBlank()) {
                    summaryResult = llmSummary;
                }
            } else {
                log.warn("Flow 总结客户端未配置（FLOW_SUMMARY_CLIENT/RESPONSE_ASSISTANT），使用本地降级总结");
            }
        } catch (Exception e) {
            log.warn("Flow LLM 总结失败，使用本地降级总结: {}", e.getMessage());
        }

        AutoAgentExecuteResultEntity summary = AutoAgentExecuteResultEntity.createSummaryResult(summaryResult, request.getSessionId());
        sendSseResult(dynamicContext, summary);

        AutoAgentExecuteResultEntity complete = AutoAgentExecuteResultEntity.createCompleteResult(request.getSessionId());
        sendSseResult(dynamicContext, complete);

        dynamicContext.setValue("finalSummary", summaryResult);
        dynamicContext.setCompleted(true);
        log.info("第五步执行完成：总结结果已输出并写入上下文");
        return "flow summary completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity executeCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    private String buildSummaryPrompt(ExecuteCommandEntity request, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String userRequest = dynamicContext.getValue("originalUserRequest");
        if (userRequest == null || userRequest.isBlank()) {
            userRequest = request.getMessage();
        }

        StringBuilder facts = new StringBuilder();
        List<Integer> stepNumbers = extractAndSortStepNumbers(dynamicContext.getValue("stepsMap"));
        for (Integer stepNumber : stepNumbers) {
            String status = dynamicContext.getValue("step" + stepNumber + "Status");
            String error = dynamicContext.getValue("step" + stepNumber + "Error");
            String result = dynamicContext.getValue("step" + stepNumber + "Result");
            facts.append("- 第").append(stepNumber).append("步 | 状态=").append(status == null ? "UNKNOWN" : status);
            if (result != null && !result.isBlank()) {
                facts.append(" | 结果=").append(result, 0, Math.min(400, result.length()));
            }
            if (error != null && !error.isBlank()) {
                facts.append(" | 错误=").append(error);
            }
            facts.append("\n");
        }

        String history = dynamicContext.getExecutionHistory() == null ? "" : dynamicContext.getExecutionHistory().toString();
        return """
                你是任务执行复盘助手，请根据执行事实产出一份可存入记忆的高质量总结。
                要求：
                1) 先给出最终状态（SUCCESS/PARTIAL_SUCCESS/FAILED）。
                2) 总结关键完成项（最多3条）。
                3) 总结问题记录（失败步骤与原因，最多3条）。
                4) 给出后续建议（最多2条）。
                5) 只基于输入事实，不要编造。
                                
                用户原始任务：
                %s
                                
                步骤执行事实：
                %s
                                
                执行历史日志：
                %s
                """.formatted(userRequest, facts, history);
    }

    private String buildFallbackSummary(ExecuteCommandEntity request, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String userRequest = dynamicContext.getValue("originalUserRequest");
        if (userRequest == null || userRequest.isBlank()) {
            userRequest = request.getMessage();
        }

        List<Integer> stepNumbers = extractAndSortStepNumbers(dynamicContext.getValue("stepsMap"));
        List<String> successItems = new ArrayList<>();
        List<String> failedItems = new ArrayList<>();
        for (Integer stepNumber : stepNumbers) {
            String status = dynamicContext.getValue("step" + stepNumber + "Status");
            String error = dynamicContext.getValue("step" + stepNumber + "Error");
            String result = dynamicContext.getValue("step" + stepNumber + "Result");
            if ("FAILED_WITH_ERROR".equals(status) || "FAILED".equals(status) || (error != null && !error.isBlank())) {
                failedItems.add("第" + stepNumber + "步失败: " + (error == null ? "未知错误" : error));
            } else if (result != null && !result.isBlank()) {
                successItems.add("第" + stepNumber + "步完成: " + result.substring(0, Math.min(120, result.length())));
            }
        }

        String finalStatus = failedItems.isEmpty() ? "SUCCESS" : (successItems.isEmpty() ? "FAILED" : "PARTIAL_SUCCESS");
        StringBuilder summary = new StringBuilder();
        summary.append("## Flow 执行总结\n")
                .append("- 任务目标: ").append(userRequest).append("\n")
                .append("- 最终状态: ").append(finalStatus).append("\n")
                .append("- 成功步骤数: ").append(successItems.size()).append("\n")
                .append("- 失败步骤数: ").append(failedItems.size()).append("\n");
        if (!successItems.isEmpty()) {
            summary.append("### 关键完成项\n");
            successItems.forEach(item -> summary.append("- ").append(item).append("\n"));
        }
        if (!failedItems.isEmpty()) {
            summary.append("### 问题记录\n");
            failedItems.forEach(item -> summary.append("- ").append(item).append("\n"));
        }
        return summary.toString();
    }

    private List<Integer> extractAndSortStepNumbers(Map<String, String> stepsMap) {
        if (stepsMap == null || stepsMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> stepNumbers = new ArrayList<>();
        Pattern numberPattern = Pattern.compile("第(\\d+)步");
        for (String key : stepsMap.keySet()) {
            Matcher matcher = numberPattern.matcher(key);
            if (matcher.find()) {
                stepNumbers.add(Integer.parseInt(matcher.group(1)));
            }
        }
        stepNumbers.sort(Integer::compareTo);
        return stepNumbers;
    }
}

