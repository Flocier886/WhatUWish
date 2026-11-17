package com.flocier.domain.agent.service.armory.node.factory.element.queryRewriter;

import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;

/**
 * 基于 OpenAI ChatModel 的 Query Rewriter。
 *
 * - 使用 {@code rag.query-rewriter.model} 指定的模型；未配置时默认 gpt-5.5
 * - base-url / api-key 从 Spring 配置读取（application-dev.yml 中 spring.ai.openai.*）
 * - 输出：单行、可用于检索的改写 query（不输出解释）
 *
 * 由 Spring 注册为 Bean（{@link org.springframework.stereotype.Component}），配置通过 {@code @Value} 注入。
 */
@Component("OpenAiQueryRewriter")
public class OpenAiQueryRewriter implements EnhancedRagRetriever.QueryRewriter {

    private static final String DEFAULT_MODEL = "gpt-5.5";

    private static final String REWRITE_SYSTEM_PROMPT = """
            你是用于 RAG 检索的「查询改写」模块。
            请把用户的自然语言问题改写成一条简短、利于向量/关键词检索的查询语句。
            规则：
            - 保持原意，不要编造事实。
            - 保留关键实体、产品名、ID、数字、日期与约束条件。
            - 若用户输入中包含代码或 API 名等术语，请原样保留（区分大小写）。
            - 去掉客套话、口头语等无关语气词。
            - 只输出改写后的查询：单行文本，不要解释，不要加引号，不要输出仅标点的一行。
            """;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    /**
     * 查询改写是 RAG 主链的一部分，不能把已不可用的模型名硬编码在领域实现中。
     * 环境可用模型变化时只需调整配置，不影响旧 Advisor 与 ReAct Tool 的共享内核。
     */
    @Value("${rag.query-rewriter.model:" + DEFAULT_MODEL + "}")
    private String model = DEFAULT_MODEL;

    private volatile OpenAiChatModel chatModel;

    public OpenAiQueryRewriter() {
    }

    /**
     * 便于手动 new / 测试时注入配置。
     */
    public OpenAiQueryRewriter(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public String rewrite(String userQuery, Map<String, Object> context) {
        if (!StringUtils.hasText(userQuery)) {
            return userQuery;
        }

        OpenAiChatModel model = getOrCreateModel();

        Prompt prompt = new Prompt(REWRITE_SYSTEM_PROMPT + "\n\n用户问题：\n" + userQuery.trim());
        ChatResponse response = model.call(prompt);

        String text = (response == null || response.getResult() == null || response.getResult().getOutput() == null)
                ? null
                : response.getResult().getOutput().getText();

        String rewritten = sanitizeOneLine(text);
        if (!StringUtils.hasText(rewritten)) {
            return userQuery;
        }

        // 允许上层把改写结果透出（可选）
        if (context != null) {
            context.put("qa_query_rewriter", this.getClass().getSimpleName());
        }
        return rewritten;
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
            if (!StringUtils.hasText(this.baseUrl) || !StringUtils.hasText(this.apiKey)) {
                throw new IllegalStateException("spring.ai.openai.base-url / spring.ai.openai.api-key is required for OpenAiQueryRewriter");
            }

            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(this.baseUrl)
                    .apiKey(this.apiKey)
                    .build();

            this.chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(StringUtils.hasText(this.model) ? this.model.trim() : DEFAULT_MODEL)
                            .temperature(0.2)
                            .build())
                    .build();

            return Objects.requireNonNull(this.chatModel);
        }
    }

    private static String sanitizeOneLine(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String s = text.trim();
        if (s.startsWith("```")) {
            s = s.replace("```", "").trim();
        }
        // 去掉最外层引号
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("“") && s.endsWith("”"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // 只取第一行，避免模型输出多行解释
        int idx = s.indexOf('\n');
        if (idx >= 0) {
            s = s.substring(0, idx).trim();
        }
        // 避免空白或纯标点
        if (!StringUtils.hasText(s)) {
            return null;
        }
        return s;
    }
}

