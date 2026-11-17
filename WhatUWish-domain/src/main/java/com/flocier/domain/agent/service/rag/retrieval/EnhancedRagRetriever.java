package com.flocier.domain.agent.service.rag.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 旧 Advisor 与新 ReAct Tool 共用的增强检索内核。
 *
 * <p>固定主链：Query Rewrite -> Vector Recall -> BM25 Recall -> RRF -> Rerank。
 * 调用入口自行决定如何消费 {@link EnhancedRagRetrievalResult}：旧 Advisor 注入 Prompt，
 * ReAct Tool 则生成受控 observation。</p>
 */
public class EnhancedRagRetriever {

    private static final Pattern KNOWLEDGE_FILTER_PATTERN =
            Pattern.compile("(knowledge|knowledge_tag)\\s*==\\s*['\"]([^'\"]+)['\"]");

    private final VectorStore vectorStore;
    private final SearchRequest vectorSearchRequest;
    private final KeywordRetriever keywordRetriever;
    private final int keywordTopK;
    private final QueryRewriter queryRewriter;
    private final Reranker reranker;
    private final int rerankTopK;

    public EnhancedRagRetriever(VectorStore vectorStore,
                                SearchRequest vectorSearchRequest,
                                KeywordRetriever keywordRetriever,
                                int keywordTopK,
                                QueryRewriter queryRewriter,
                                Reranker reranker,
                                int rerankTopK) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.vectorSearchRequest = Objects.requireNonNull(vectorSearchRequest, "vectorSearchRequest must not be null");
        this.keywordRetriever = keywordRetriever;
        this.keywordTopK = Math.max(keywordTopK, 0);
        this.queryRewriter = queryRewriter;
        this.reranker = reranker;
        this.rerankTopK = rerankTopK <= 0 ? vectorSearchRequest.getTopK() : rerankTopK;
    }

    /**
     * 执行完整增强检索。legacyContext 仅用于承接旧 Advisor 已有的 qa_* 过滤约定；
     * 新 ReAct Tool 只传受控 knowledgeTag，不向该 map 写入底层表达式。
     */
    public EnhancedRagRetrievalResult retrieve(String query,
                                                String knowledgeTag,
                                                Map<String, Object> legacyContext) {
        return retrieve(query, knowledgeTag, this.rerankTopK, legacyContext);
    }

    /**
     * @param finalTopK 对调用方暴露的最终文档上限；不会缩小 Vector/BM25 的候选召回预算。
     */
    public EnhancedRagRetrievalResult retrieve(String query,
                                                String knowledgeTag,
                                                int finalTopK,
                                                Map<String, Object> legacyContext) {
        String originalQuery = query == null ? "" : query.trim();
        Map<String, Object> context = new LinkedHashMap<>();
        if (legacyContext != null) {
            context.putAll(legacyContext);
        }
        if (StringUtils.hasText(knowledgeTag)) {
            context.put("qa_knowledge_tag", knowledgeTag.trim());
        }

        List<String> degradations = new ArrayList<>();
        String rewrittenQuery = rewriteQuery(originalQuery, context, degradations);
        context.put("qa_original_query", originalQuery);
        context.put("qa_rewritten_query", rewrittenQuery);

        Filter.Expression vectorFilterExpression = resolveVectorFilterExpression(context, knowledgeTag);
        injectKeywordFilterFromVectorExpressionIfNeeded(context, vectorFilterExpression);

        List<Document> vectorDocuments;
        try {
            SearchRequest request = SearchRequest.from(this.vectorSearchRequest)
                    .query(rewrittenQuery)
                    .filterExpression(vectorFilterExpression)
                    .build();
            vectorDocuments = safeDocuments(this.vectorStore.similaritySearch(request));
        } catch (Exception exception) {
            degradations.add("vector_unavailable");
            context.put("qa_vector_failure", safeMessage(exception));
            return result(originalQuery, rewrittenQuery, textOf(context.get("qa_knowledge_tag")), false,
                    safeMessage(exception), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), context, degradations);
        }
        context.put("qa_vector_retrieved_documents", vectorDocuments);

        List<Document> keywordDocuments = recallKeywords(rewrittenQuery, context, degradations);
        context.put("qa_keyword_retrieved_documents", keywordDocuments);
        if (Boolean.TRUE.equals(context.get("qa_keyword_fallback"))) {
            addDegradation(degradations, "bm25_fallback");
        }

        List<ScoredDocument> coarse = fuseCoarseRank(vectorDocuments, keywordDocuments);
        List<Document> coarseDocuments = coarse.stream().map(ScoredDocument::getDocument).toList();
        context.put("qa_coarse_rank_documents", coarseDocuments);

        List<Document> rerankDocuments = List.of();
        Map<String, Double> rerankScores = Map.of();
        List<Document> finalDocuments = coarseDocuments;
        if (this.reranker != null && !coarse.isEmpty()) {
            try {
                List<ScoredDocument> reranked = new ArrayList<>(Optional.ofNullable(
                        this.reranker.rerank(rewrittenQuery, coarseDocuments, context))
                        .orElseThrow(() -> new IllegalStateException("reranker returned null")));
                reranked.sort(Comparator.comparingDouble(ScoredDocument::getScore).reversed());
                List<ScoredDocument> rerankedTop = reranked.stream().limit(effectiveFinalTopK(finalTopK)).toList();
                rerankDocuments = rerankedTop.stream().map(ScoredDocument::getDocument).toList();
                rerankScores = rerankedTop.stream().collect(Collectors.toMap(
                        item -> fingerprint(item.getDocument()), ScoredDocument::getScore, (left, right) -> left, LinkedHashMap::new));
                finalDocuments = rerankDocuments;
                context.put("qa_rerank_documents", rerankDocuments);
                context.put("qa_rerank_scores", rerankScores);
                if (Boolean.TRUE.equals(context.get("qa_rerank_fallback"))) {
                    addDegradation(degradations, "rerank_fallback");
                }
            } catch (Exception exception) {
                addDegradation(degradations, "rerank_fallback");
                context.put("qa_rerank_failure", safeMessage(exception));
            }
        } else if (finalDocuments.size() > effectiveFinalTopK(finalTopK)) {
            finalDocuments = finalDocuments.stream().limit(effectiveFinalTopK(finalTopK)).toList();
        }
        context.put("qa_retrieved_documents", finalDocuments);

        return result(originalQuery, rewrittenQuery, textOf(context.get("qa_knowledge_tag")), true, null,
                vectorDocuments, keywordDocuments, coarseDocuments, rerankDocuments, rerankScores, finalDocuments, context, degradations);
    }

    private String rewriteQuery(String originalQuery, Map<String, Object> context, List<String> degradations) {
        if (this.queryRewriter == null || !StringUtils.hasText(originalQuery)) {
            return originalQuery;
        }
        try {
            String rewritten = this.queryRewriter.rewrite(originalQuery, context);
            if (StringUtils.hasText(rewritten)) {
                return rewritten.trim();
            }
            addDegradation(degradations, "query_rewrite_fallback");
            return originalQuery;
        } catch (Exception exception) {
            addDegradation(degradations, "query_rewrite_fallback");
            context.put("qa_query_rewrite_failure", safeMessage(exception));
            return originalQuery;
        }
    }

    private int effectiveFinalTopK(int requestedTopK) {
        if (requestedTopK <= 0) {
            return this.rerankTopK;
        }
        return Math.min(requestedTopK, this.rerankTopK);
    }

    private List<Document> recallKeywords(String rewrittenQuery, Map<String, Object> context, List<String> degradations) {
        if (this.keywordRetriever == null || this.keywordTopK <= 0) {
            return List.of();
        }
        try {
            List<Document> documents = this.keywordRetriever.search(rewrittenQuery, this.keywordTopK, context);
            if (documents == null) {
                addDegradation(degradations, "bm25_fallback");
                return List.of();
            }
            return safeDocuments(documents);
        } catch (Exception exception) {
            addDegradation(degradations, "bm25_fallback");
            context.put("qa_keyword_failure", safeMessage(exception));
            return List.of();
        }
    }

    private Filter.Expression resolveVectorFilterExpression(Map<String, Object> context, String knowledgeTag) {
        if (context.containsKey("qa_filter_expression") && hasText(context.get("qa_filter_expression"))) {
            return new FilterExpressionTextParser().parse(String.valueOf(context.get("qa_filter_expression")));
        }
        if (StringUtils.hasText(knowledgeTag)) {
            return new FilterExpressionTextParser().parse("knowledge == '" + knowledgeTag.trim() + "'");
        }
        return this.vectorSearchRequest.getFilterExpression();
    }

    private void injectKeywordFilterFromVectorExpressionIfNeeded(Map<String, Object> context, Filter.Expression vectorFilterExpression) {
        if (hasText(context.get("qa_keyword_filter")) || hasText(context.get("qa_knowledge_tag"))) {
            return;
        }
        String raw = hasText(context.get("qa_filter_expression"))
                ? String.valueOf(context.get("qa_filter_expression"))
                : vectorFilterExpression == null ? null : vectorFilterExpression.toString();
        Matcher matcher = raw == null ? null : KNOWLEDGE_FILTER_PATTERN.matcher(raw);
        if (matcher != null && matcher.find()) {
            context.put("qa_knowledge_tag", matcher.group(2));
        }
    }

    private EnhancedRagRetrievalResult result(String originalQuery, String rewrittenQuery, String knowledgeTag,
                                              boolean vectorAvailable, String vectorFailureMessage,
                                              List<Document> vectorDocuments, List<Document> keywordDocuments,
                                              List<Document> coarseDocuments, List<Document> rerankDocuments,
                                              Map<String, Double> rerankScores, List<Document> finalDocuments,
                                              Map<String, Object> context, List<String> degradations) {
        return EnhancedRagRetrievalResult.builder()
                .originalQuery(originalQuery)
                .rewrittenQuery(rewrittenQuery)
                .knowledgeTag(knowledgeTag)
                .vectorAvailable(vectorAvailable)
                .vectorFailureMessage(vectorFailureMessage)
                .vectorDocuments(List.copyOf(vectorDocuments))
                .keywordDocuments(List.copyOf(keywordDocuments))
                .coarseRankDocuments(List.copyOf(coarseDocuments))
                .rerankDocuments(List.copyOf(rerankDocuments))
                .rerankScores(Map.copyOf(rerankScores))
                .finalDocuments(List.copyOf(finalDocuments))
                .diagnostics(new LinkedHashMap<>(context))
                .degradations(List.copyOf(degradations))
                .build();
    }

    private List<ScoredDocument> fuseCoarseRank(List<Document> vectorDocuments, List<Document> keywordDocuments) {
        Map<String, ScoredDocument> merged = new LinkedHashMap<>();
        addRrfScores(merged, vectorDocuments, 60);
        addRrfScores(merged, keywordDocuments, 60);
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::getScore).reversed())
                .toList();
    }

    private void addRrfScores(Map<String, ScoredDocument> merged, List<Document> documents, int rankConstant) {
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            if (document == null) {
                continue;
            }
            double increment = 1.0d / (rankConstant + index + 1);
            merged.compute(fingerprint(document), (key, existing) -> {
                if (existing == null) {
                    return new ScoredDocument(document, increment);
                }
                return new ScoredDocument(existing.getDocument(), existing.getScore() + increment);
            });
        }
    }

    private static List<Document> safeDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream().filter(Objects::nonNull).toList();
    }

    private static boolean hasText(Object value) {
        return value != null && StringUtils.hasText(String.valueOf(value));
    }

    private static void addDegradation(List<String> degradations, String degradation) {
        if (!degradations.contains(degradation)) {
            degradations.add(degradation);
        }
    }

    private static String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static String fingerprint(Document document) {
        Map<String, Object> metadata = document == null || document.getMetadata() == null ? Map.of() : document.getMetadata();
        String parentDocumentId = textOf(metadata.get("parent_document_id"));
        String chunkIndex = textOf(metadata.get("chunk_index"));
        if (StringUtils.hasText(parentDocumentId) && StringUtils.hasText(chunkIndex)) {
            return "parentChunk:" + parentDocumentId + "#" + chunkIndex;
        }
        String documentId = textOf(metadata.get("doc_id"));
        if (StringUtils.hasText(documentId) && StringUtils.hasText(chunkIndex)) {
            return "docChunk:" + documentId + "#" + chunkIndex;
        }
        String chunkId = textOf(metadata.get("chunk_id"));
        if (StringUtils.hasText(chunkId)) {
            return "chunk:" + chunkId;
        }
        return "text:" + sha256(document == null ? "" : textOf(document.getText()));
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return String.valueOf(input.hashCode());
        }
    }

    public record ScoredDocument(Document document, double score) {
        public Document getDocument() {
            return document;
        }

        public double getScore() {
            return score;
        }
    }

    public interface QueryRewriter {
        String rewrite(String userQuery, Map<String, Object> context);
    }

    public interface KeywordRetriever {
        List<Document> search(String query, int topK, Map<String, Object> context);
    }

    public interface Reranker {
        List<ScoredDocument> rerank(String query, List<Document> candidates, Map<String, Object> context);
    }
}
