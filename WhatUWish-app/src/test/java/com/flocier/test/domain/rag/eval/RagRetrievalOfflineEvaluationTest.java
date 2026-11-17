package com.flocier.test.domain.rag.eval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.agent.model.vo.AiClientAdvisorTypeEnumVO;
import com.flocier.domain.agent.model.vo.AiClientAdvisorVO;
import com.flocier.domain.agent.service.armory.node.factory.element.RagAnswerAdvanceAdvisor;
import com.flocier.domain.agent.service.armory.support.RagAdvanceAdvisorBeansProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class RagRetrievalOfflineEvaluationTest {

    private static final String DEFAULT_SAMPLE_RESOURCE = "evaluation/rag-retrieval-samples.json";
    private static final String DEFAULT_SCENARIO_NAME = "baseline";
    private static final int DEFAULT_VECTOR_TOP_K = 12;
    private static final int DEFAULT_KEYWORD_TOP_K = 12;
    private static final int DEFAULT_RERANK_TOP_K = 8;

    @Autowired
    @Qualifier("vectorStore")
    private VectorStore vectorStore;

    @Autowired
    private RagAdvanceAdvisorBeansProvider ragAdvanceAdvisorBeansProvider;

    @Test
    public void test_evaluateVectorOnlyAndEnhancedRagRetrieval() throws Exception {
        EvaluationConfig config = buildConfig();
        List<RagRetrievalEvalSample> samples = loadSamples(config.sampleResource());
        Assume.assumeFalse("评测样本为空，请先补充 " + config.sampleResource(), samples.isEmpty());

        List<RagRetrievalEvalCaseResult> vectorOnlyResults = new ArrayList<>();
        List<RagRetrievalEvalCaseResult> enhancedResults = new ArrayList<>();

        RagAnswerAdvanceAdvisor enhancedAdvisor = buildEnhancedAdvisor(config);

        for (RagRetrievalEvalSample sample : samples) {
            List<String> vectorRetrieved = normalizeChunkIds(searchVectorOnly(sample, config));
            vectorOnlyResults.add(RagRetrievalEvaluator.evaluateCase(sample, vectorRetrieved));

            List<String> enhancedRetrieved = normalizeChunkIds(searchWithEnhancedAdvisor(sample, enhancedAdvisor));
            enhancedResults.add(RagRetrievalEvaluator.evaluateCase(sample, enhancedRetrieved));
        }

        RagRetrievalMetrics vectorMetrics = RagRetrievalEvaluator.summarize(vectorOnlyResults);
        RagRetrievalMetrics enhancedMetrics = RagRetrievalEvaluator.summarize(enhancedResults);

        log.info("RAG evaluation scenario={} sampleResource={} vectorTopK={} keywordTopK={} rerankTopK={}",
                config.scenarioName(), config.sampleResource(), config.vectorTopK(), config.keywordTopK(), config.rerankTopK());
        log.info("\n{}", RagRetrievalEvaluator.toMarkdown(config.scenarioName() + "_vector_only", vectorMetrics, vectorOnlyResults));
        log.info("\n{}", RagRetrievalEvaluator.toMarkdown(config.scenarioName() + "_rag_enhanced", enhancedMetrics, enhancedResults));
    }

    private EvaluationConfig buildConfig() {
        String sampleResource = System.getProperty("rag.eval.sample", DEFAULT_SAMPLE_RESOURCE);
        String scenarioName = System.getProperty("rag.eval.scenario", DEFAULT_SCENARIO_NAME);
        int vectorTopK = Integer.getInteger("rag.eval.vectorTopK", DEFAULT_VECTOR_TOP_K);
        int keywordTopK = Integer.getInteger("rag.eval.keywordTopK", DEFAULT_KEYWORD_TOP_K);
        int rerankTopK = Integer.getInteger("rag.eval.rerankTopK", DEFAULT_RERANK_TOP_K);
        return new EvaluationConfig(sampleResource, scenarioName, vectorTopK, keywordTopK, rerankTopK);
    }

    private List<RagRetrievalEvalSample> loadSamples(String sampleResource) throws Exception {
        ClassPathResource resource = new ClassPathResource(sampleResource);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            String json = FileCopyUtils.copyToString(reader);
            return JSON.parseObject(json, new TypeReference<List<RagRetrievalEvalSample>>() {
            });
        }
    }

    private RagAnswerAdvanceAdvisor buildEnhancedAdvisor(EvaluationConfig config) {
        AiClientAdvisorVO advisorConfig = AiClientAdvisorVO.builder()
                .ragAnswerAdvance(AiClientAdvisorVO.RagAnswerAdvance.builder()
                        .topK(config.vectorTopK())
                        .keywordRetrieverType("EsBm25KeywordRetriever")
                        .keywordTopK(config.keywordTopK())
                        .queryRewriterType("OpenAiQueryRewriter")
                        .rerankerType("BgeRerankerV2M3Client")
                        .rerankTopK(config.rerankTopK())
                        .build())
                .build();

        Advisor advisor = AiClientAdvisorTypeEnumVO.RAG_ANSWER_ADVANCE
                .createAdvisor(advisorConfig, vectorStore, ragAdvanceAdvisorBeansProvider);
        return (RagAnswerAdvanceAdvisor) advisor;
    }

    private List<Document> searchVectorOnly(RagRetrievalEvalSample sample, EvaluationConfig config) {
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(sample.getQuery())
                .topK(config.vectorTopK());
        if (StringUtils.hasText(sample.getKnowledgeTag())) {
            requestBuilder.filterExpression("knowledge == '" + sample.getKnowledgeTag() + "'");
        }
        return vectorStore.similaritySearch(requestBuilder.build());
    }

    private List<Document> searchWithEnhancedAdvisor(RagRetrievalEvalSample sample, RagAnswerAdvanceAdvisor advisor) {
        Map<String, Object> context = new HashMap<>();
        if (StringUtils.hasText(sample.getKnowledgeTag())) {
            context.put("qa_knowledge_tag", sample.getKnowledgeTag());
            context.put("qa_filter_expression", "knowledge == '" + sample.getKnowledgeTag() + "'");
        }

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(new UserMessage(sample.getQuery())).build())
                .context(context)
                .build();

        ChatClientRequest advised = advisor.before(request, null);
        Object rawRetrieved = advised.context().get("qa_retrieved_documents");
        if (!(rawRetrieved instanceof List<?> list)) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Document document) {
                documents.add(document);
            }
        }
        return documents;
    }

    private List<String> normalizeChunkIds(List<Document> documents) {
        Set<String> ids = new LinkedHashSet<>();
        for (Document document : documents) {
            String normalized = normalizeChunkId(document);
            if (StringUtils.hasText(normalized)) {
                ids.add(normalized);
            }
        }
        return new ArrayList<>(ids);
    }

    private String normalizeChunkId(Document document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Object source = document.getMetadata().get("source");
        Object parentDocumentId = document.getMetadata().get("parent_document_id");
        Object chunkIndex = document.getMetadata().get("chunk_index");

        // Evaluation prefers source-based ids so fixture files can be referenced stably.
        if (source != null && chunkIndex != null) {
            return "source:" + String.valueOf(source).trim() + "#" + String.valueOf(chunkIndex).trim();
        }

        if (parentDocumentId != null && chunkIndex != null) {
            return String.valueOf(parentDocumentId).trim() + "#" + String.valueOf(chunkIndex).trim();
        }

        Object docId = document.getMetadata().get("doc_id");
        if (docId != null && chunkIndex != null) {
            return String.valueOf(docId).trim() + "#" + String.valueOf(chunkIndex).trim();
        }

        Object chunkId = document.getMetadata().get("chunk_id");
        if (chunkId != null) {
            return String.valueOf(chunkId).trim();
        }

        return source == null ? null : "source:" + String.valueOf(source).trim();
    }

    private record EvaluationConfig(String sampleResource,
                                    String scenarioName,
                                    int vectorTopK,
                                    int keywordTopK,
                                    int rerankTopK) {
    }
}
