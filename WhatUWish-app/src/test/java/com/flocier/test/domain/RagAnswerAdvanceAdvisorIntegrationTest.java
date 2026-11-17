package com.flocier.test.domain;

import com.flocier.domain.agent.model.vo.AiClientAdvisorTypeEnumVO;
import com.flocier.domain.agent.model.vo.AiClientAdvisorVO;
import com.flocier.domain.agent.service.armory.support.RagAdvanceAdvisorBeansProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;

import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * {@link com.flocier.domain.agent.service.armory.node.factory.element.RagAnswerAdvanceAdvisor} 端到端 smoke 测试。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>我无法访问你机器上的 PostgreSQL；本测试在<strong>你本地/CI 运行 JUnit</strong>时，会通过 Spring 注入的 {@link VectorStore} 连到你的 pgvector，自动抽样文档并生成问题。</li>
 *   <li>LLM 使用 {@code application.yml} / profile 下的 {@code spring.ai.openai.base-url}、{@code api-key}（与 {@code OpenAiQueryRewriter} 一致）。</li>
 *   <li>可选开启 query 改写与 rerank（需 rerank 容器与网关可达）；关掉时仍可走「纯向量粗排」路径。</li>
 * </ul>
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class RagAnswerAdvanceAdvisorIntegrationTest {

    /**
     * 与入库时 {@code RagService} 写入的 metadata 一致时可填过滤表达式；不需要过滤则保持 null。
     * 示例（Spring AI 向量过滤语法视版本而定）：{@code knowledge == 'yourTag'}
     */
    private static final String KNOWLEDGE_FILTER_EXPRESSION = null;

    @Autowired
    private Environment environment;

    @Autowired
    @Qualifier("vectorStore")
    private VectorStore vectorStore;

    @Autowired
    private RagAdvanceAdvisorBeansProvider ragAdvanceAdvisorBeansProvider;

    private OpenAiChatModel chatModel;

    @Before
    public void buildChatModel() {
        String baseUrl = environment.getRequiredProperty("spring.ai.openai.base-url");
        String apiKey = environment.getRequiredProperty("spring.ai.openai.api-key");
        String model = environment.getProperty("spring.ai.openai.chat.options.model", "gpt-4.1");

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.3)
                        .build())
                .build();

        log.info("RAG 集成测试 LLM: base-url={}, model={}", baseUrl, model);
    }

    /**
     * 仅从向量库抽样若干条，打印到日志，便于你对照真实入库内容改 {@link #KNOWLEDGE_FILTER_EXPRESSION} 或手工出题。
     */
    @Test
    public void test_peekVectorStore_sampleChunks() {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("核心内容与概念")
                .topK(5)
                .filterExpression(KNOWLEDGE_FILTER_EXPRESSION)
                .build());

        Assume.assumeFalse("向量库无命中时请确认 PostgreSQL/pgvector 中有数据且 embedding 正常", hits.isEmpty());

        int i = 0;
        for (Document d : hits) {
            String text = d.getText() == null ? "" : d.getText();
            String excerpt = text.length() > 300 ? text.substring(0, 300) + "..." : text;
            log.info("[向量抽样 {}] metadata={}\n{}", i++, d.getMetadata(), excerpt);
        }
    }

    /**
     * 向量召回 + 可选 Query 改写 + 可选 Rerank；调用结束后从 {@link ChatResponse#getMetadata()} 打印
     * {@code qa_rewritten_query}、向量命中、精排结果（与 {@link com.flocier.domain.agent.service.armory.node.factory.element.RagAnswerAdvanceAdvisor#after} 写入的键一致）。
     */
    @Test
    public void test_ragAdvance_vectorOnly_smoke() {

        String userQuestion = "问题：为啥我加个 starter 配置就少写一堆？背后啥机制？";

        AiClientAdvisorVO vo = AiClientAdvisorVO.builder()
                .ragAnswerAdvance(AiClientAdvisorVO.RagAnswerAdvance.builder()
                        .topK(8)
                        .filterExpression(KNOWLEDGE_FILTER_EXPRESSION)
                        .queryRewriterType("OpenAiQueryRewriter")
                        .keywordRetrieverType("EsBm25KeywordRetriever")
                        .keywordTopK(8)
                        .rerankerType("BgeRerankerV2M3Client")
                        .rerankTopK(4)
                        .build())
                .build();

        Advisor advisor = AiClientAdvisorTypeEnumVO.RAG_ANSWER_ADVANCE.createAdvisor(vo, vectorStore, ragAdvanceAdvisorBeansProvider);
        ChatClient client = ChatClient.builder(chatModel)
                .defaultTemplateRenderer(new NoOpTemplateRenderer())
                .defaultAdvisors(advisor)
                .build();

        ChatResponse chatResponse = client.prompt(Prompt.builder()
                        .messages(new UserMessage(userQuestion))
                        .build())
                .call()
                .chatResponse();

        logRagPipelineMetadata(chatResponse);

        String answer = chatResponse.getResult().getOutput().getText();

        log.info("用户问（摘录驱动）: {}", userQuestion);
        log.info("模型答: {}", answer);
        assertTrue(StringUtils.hasText(answer));
    }

    /**
     * 完整链路（可选）：开启 {@link com.flocier.domain.agent.model.vo.AiQueryRewriterTypeEnumVO} 与 {@link com.flocier.domain.agent.model.vo.AiRerankerTypeEnumVO} 所配置的实现；
     * 需 {@code rerank.*} 与网关可用，否则 rerank 会降级、bge 容器需可达。
     */
    @Test
    public void test_ragAdvance_withRewriterAndRerank_whenConfigured() {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("定义 原理 步骤")
                .topK(3)
                .filterExpression(KNOWLEDGE_FILTER_EXPRESSION)
                .build());
        Assume.assumeFalse("向量库无命中时跳过", hits.isEmpty());

        String excerpt = buildExcerpt(hits.get(0));
        String userQuestion = "请严格依据检索到的知识作答：" + excerpt + " —— 请用一句话说明这段文字的主题。";

        AiClientAdvisorVO.RagAnswerAdvance.RagAnswerAdvanceBuilder b = AiClientAdvisorVO.RagAnswerAdvance.builder()
                .topK(12)
                .filterExpression(KNOWLEDGE_FILTER_EXPRESSION)
                .keywordTopK(0)
                .rerankTopK(3);

        if (StringUtils.hasText(environment.getProperty("spring.ai.openai.api-key"))) {
            b.queryRewriterType("OpenAiQueryRewriter");
        }
        if (StringUtils.hasText(environment.getProperty("rerank.base-url"))) {
            b.rerankerType("BgeRerankerV2M3Client");
        }

        AiClientAdvisorVO vo = AiClientAdvisorVO.builder()
                .ragAnswerAdvance(b.build())
                .build();

        Advisor advisor = AiClientAdvisorTypeEnumVO.RAG_ANSWER_ADVANCE.createAdvisor(vo, vectorStore, ragAdvanceAdvisorBeansProvider);
        ChatClient client = ChatClient.builder(chatModel)
                .defaultTemplateRenderer(new NoOpTemplateRenderer())
                .defaultAdvisors(advisor)
                .build();

        String answer = client.prompt(Prompt.builder()
                        .messages(new UserMessage(userQuestion))
                        .build())
                .call()
                .content();

        log.info("用户问: {}", userQuestion);
        log.info("模型答: {}", answer);
        assertTrue(StringUtils.hasText(answer));
    }

    private static String buildExcerpt(Document d) {
        if (d == null || !StringUtils.hasText(d.getText())) {
            return "(空文档)";
        }
        String t = d.getText().strip();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    /**
     * 打印 {@link com.flocier.domain.agent.service.armory.node.factory.element.RagAnswerAdvanceAdvisor}
     * 在 {@code after()} 中写入 {@link ChatResponseMetadata} 的自定义键（通过 {@link ChatResponseMetadata#get(String)} 读取）。
     */
    private void logRagPipelineMetadata(ChatResponse chatResponse) {
        if (chatResponse == null) {
            log.warn("ChatResponse 为 null，无法打印 RAG 调试信息");
            return;
        }
        ChatResponseMetadata meta = chatResponse.getMetadata();
        if (meta == null) {
            // (Object) 避免与 info(String, Throwable) 重载在编译期歧义
            log.warn("ChatResponse.getMetadata() 为 null，chatResponse={}", (Object) chatResponse);
            return;
        }

        log.info("========== RagAnswerAdvanceAdvisor 链路（ChatResponseMetadata）==========");
        log.info("[原始用户句] qa_original_query = {}", (Object) meta.get("qa_original_query"));
        log.info("[Query 改写后] qa_rewritten_query = {}", (Object) meta.get("qa_rewritten_query"));
        logDocumentList("[向量检索] qa_vector_retrieved_documents", meta.get("qa_vector_retrieved_documents"));
        logDocumentList("[关键词检索] qa_keyword_retrieved_documents", meta.get("qa_keyword_retrieved_documents"));
        logDocumentList("[粗排融合后] qa_coarse_rank_documents", meta.get("qa_coarse_rank_documents"));
        logDocumentList("[Rerank 精排后] qa_rerank_documents", meta.get("qa_rerank_documents"));
        log.info("[Rerank 分数 map] qa_rerank_scores = {}", (Object) meta.get("qa_rerank_scores"));
        logDocumentList("[最终注入 prompt 的文档] qa_retrieved_documents", meta.get("qa_retrieved_documents"));
        log.info("========================================================================");
    }

    private static void logDocumentList(String title, Object raw) {
        if (raw == null) {
            log.info("{}: (null)", title);
            return;
        }
        if (!(raw instanceof List<?> list)) {
            log.info("{}: {}", title, (Object) raw);
            return;
        }
        if (list.isEmpty()) {
            log.info("{}: (empty list)", title);
            return;
        }
        int i = 0;
        for (Object o : list) {
            if (o instanceof Document d) {
                String tx = d.getText();
                String snippet = tx == null ? "" : (tx.length() > 280 ? tx.substring(0, 280) + "..." : tx);
                log.info("{} #{} metadata={}\ntextSnippet=\n{}", title, i++, (Object) d.getMetadata(), snippet);
            } else {
                log.info("{} #{} {}", title, i++, (Object) o);
            }
        }
    }
}
