package com.flocier.infrastructure.persistent.memory;

import com.flocier.domain.agent.model.entity.ShortTermMemoryRecordEntity;
import com.flocier.infrastructure.persistent.IMemorySummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component("openAiMemorySummaryService")
public class OpenAiMemorySummaryService implements IMemorySummaryService {

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是对话短期记忆压缩助手。
            你的任务是把「已有摘要 + 新增历史对话片段」合并成一份更短但信息密度更高的摘要。
            输出要求：
            1. 保留关键实体、约束、用户偏好、待办事项、明确结论。
            2. 保留时间/数字/ID/条件等可执行信息，不要编造。
            3. 去掉寒暄、重复表述和低价值细节。
            4. 输出简洁中文，使用要点列表，每行一条。
            5. 只输出摘要本身，不要解释处理过程。
            """;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${memory.short-term.summary.model:gpt-4.1}")
    private String model;

    private volatile OpenAiChatModel chatModel;

    @Override
    public String summarize(String existedSummary, List<ShortTermMemoryRecordEntity> overflowRecords) {
        if (overflowRecords == null || overflowRecords.isEmpty()) {
            return existedSummary;
        }
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)) {
            return existedSummary;
        }

        try {
            OpenAiChatModel modelClient = getOrCreateModel();
            String promptText = buildPrompt(existedSummary, overflowRecords);
            //log.info("promptText内容: {}",promptText);
            ChatResponse response = modelClient.call(new Prompt(promptText));
            String output = response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? null
                    : response.getResult().getOutput().getText();
            String sanitized = sanitize(output);
            //log.info("模型生成摘要：{}",output);
            if (StringUtils.hasText(sanitized)) {
                return sanitized;
            }
        } catch (Exception e) {
            log.warn("调用记忆摘要模型失败，降级到拼接摘要: {}", e.getMessage());
        }
        return existedSummary;
    }

    private OpenAiChatModel getOrCreateModel() {
        OpenAiChatModel local = this.chatModel;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (this.chatModel != null) {
                return this.chatModel;
            }
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(this.baseUrl)
                    .apiKey(this.apiKey)
                    .build();
            this.chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(this.model)
                            .temperature(0.2)
                            .build())
                    .build();
            return Objects.requireNonNull(this.chatModel);
        }
    }

    private String buildPrompt(String existedSummary, List<ShortTermMemoryRecordEntity> overflowRecords) {
        StringBuilder builder = new StringBuilder();
        builder.append(SUMMARY_SYSTEM_PROMPT).append("\n\n");
        builder.append("已有摘要：\n");
        builder.append(StringUtils.hasText(existedSummary) ? existedSummary : "(无)").append("\n\n");
        builder.append("新增历史片段：\n");
        for (ShortTermMemoryRecordEntity record : overflowRecords) {
            builder.append("- 第").append(record.getRound()).append("轮")
                    .append(" | Q: ").append(record.getUserQuestion())
                    .append(" | A: ").append(record.getFinalAnswer())
                    .append("\n");
        }
        return builder.toString();
    }

    private String sanitize(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String value = text.trim();
        if (value.startsWith("```")) {
            value = value.replace("```", "").trim();
        }
        return StringUtils.hasText(value) ? value : null;
    }
}
