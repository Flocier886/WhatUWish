package com.flocier.domain.agent.service.rag.retrieval;

import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever.ScoredDocument;
import com.flocier.domain.agent.service.armory.node.factory.element.queryRewriter.OpenAiQueryRewriter;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EnhancedRagRetrieverTest {

    @Test
    public void shouldDefaultQueryRewriterToCurrentConfiguredRuntimeModel() {
        OpenAiQueryRewriter rewriter = new OpenAiQueryRewriter("https://api.example.com", "test-key");

        assertEquals("gpt-5.5", ReflectionTestUtils.getField(rewriter, "model"));
    }

    @Test
    public void shouldReuseFullEnhancedPipelineAndApplyKnowledgeTagToVectorAndKeywordRecall() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document vectorFirst = document("v-1", "vector Redis cache content", "vector-kb", 0);
        Document shared = document("shared", "shared Redis cache content", "vector-kb", 1);
        Document keywordFirst = document("k-1", "keyword Redis eviction content", "vector-kb", 2);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorFirst, shared));

        List<String> keywordTags = new ArrayList<>();
        EnhancedRagRetriever.KeywordRetriever keywordRetriever = (query, topK, context) -> {
            keywordTags.add(String.valueOf(context.get("qa_knowledge_tag")));
            assertEquals("redis cache lookup", query);
            assertEquals(6, topK);
            return List.of(shared, keywordFirst);
        };
        EnhancedRagRetriever.QueryRewriter rewriter = (query, context) -> "redis cache lookup";
        EnhancedRagRetriever.Reranker reranker = (query, candidates, context) -> List.of(
                new ScoredDocument(keywordFirst, 0.98d),
                new ScoredDocument(shared, 0.80d),
                new ScoredDocument(vectorFirst, 0.70d)
        );

        EnhancedRagRetriever retriever = new EnhancedRagRetriever(
                vectorStore, SearchRequest.builder().topK(8).build(), keywordRetriever, 6, rewriter, reranker, 3);
        EnhancedRagRetrievalResult result = retriever.retrieve("Redis 怎么做缓存淘汰", "vector-kb", 2, new LinkedHashMap<>());

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertEquals("redis cache lookup", requestCaptor.getValue().getQuery());
        assertTrue(String.valueOf(requestCaptor.getValue().getFilterExpression()).contains("vector-kb"));
        assertEquals(List.of("vector-kb"), keywordTags);
        assertEquals("redis cache lookup", result.getRewrittenQuery());
        assertEquals(2, result.getVectorDocuments().size());
        assertEquals(2, result.getKeywordDocuments().size());
        assertEquals(3, result.getCoarseRankDocuments().size());
        assertEquals(2, result.getFinalDocuments().size());
        assertEquals("keyword Redis eviction content", result.getFinalDocuments().get(0).getText());
        assertTrue(result.getDegradations().isEmpty());
    }

    @Test
    public void shouldFallbackFromRewriteBm25AndRerankWithoutDiscardingVectorDocuments() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document vectorDocument = document("v-1", "only vector evidence", "kb", 0);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorDocument));
        EnhancedRagRetriever retriever = new EnhancedRagRetriever(
                vectorStore,
                SearchRequest.builder().topK(5).build(),
                (query, topK, context) -> {
                    throw new IllegalStateException("es unavailable");
                },
                5,
                (query, context) -> {
                    throw new IllegalStateException("rewrite unavailable");
                },
                (query, candidates, context) -> {
                    throw new IllegalStateException("rerank unavailable");
                },
                3
        );

        EnhancedRagRetrievalResult result = retriever.retrieve("original query", "kb", 3, Map.of());

        assertTrue(result.isVectorAvailable());
        assertEquals("original query", result.getRewrittenQuery());
        assertEquals(List.of(vectorDocument), result.getFinalDocuments());
        assertTrue(result.getDegradations().contains("query_rewrite_fallback"));
        assertTrue(result.getDegradations().contains("bm25_fallback"));
        assertTrue(result.getDegradations().contains("rerank_fallback"));
    }

    @Test
    public void shouldFailOnlyWhenVectorRecallIsUnavailable() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("pgvector unavailable"));
        EnhancedRagRetriever retriever = new EnhancedRagRetriever(
                vectorStore, SearchRequest.builder().topK(5).build(), null, 0, null, null, 3);

        EnhancedRagRetrievalResult result = retriever.retrieve("need internal fact", null, 3, Map.of());

        assertFalse(result.isVectorAvailable());
        assertTrue(result.getVectorFailureMessage().contains("pgvector unavailable"));
        assertTrue(result.getFinalDocuments().isEmpty());
        assertTrue(result.getDegradations().contains("vector_unavailable"));
    }

    @Test
    public void shouldExposeFallbackMarkersReturnedByExistingBm25AndRerankAdapters() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document vectorDocument = document("v-1", "vector evidence", "kb", 0);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorDocument));
        EnhancedRagRetriever retriever = new EnhancedRagRetriever(
                vectorStore,
                SearchRequest.builder().topK(5).build(),
                (query, topK, context) -> {
                    context.put("qa_keyword_fallback", true);
                    context.put("qa_keyword_failure", "request_failed");
                    return List.of();
                },
                5,
                null,
                (query, candidates, context) -> {
                    context.put("qa_rerank_fallback", true);
                    context.put("qa_rerank_failure", "request_failed");
                    return candidates.stream().map(document -> new ScoredDocument(document, 0d)).toList();
                },
                3
        );

        EnhancedRagRetrievalResult result = retriever.retrieve("need evidence", null, 3, Map.of());

        assertEquals(List.of(vectorDocument), result.getFinalDocuments());
        assertTrue(result.getDegradations().contains("bm25_fallback"));
        assertTrue(result.getDegradations().contains("rerank_fallback"));
        assertEquals("request_failed", result.getDiagnostics().get("qa_keyword_failure"));
        assertEquals("request_failed", result.getDiagnostics().get("qa_rerank_failure"));
    }

    private static Document document(String id, String text, String knowledgeTag, int chunkIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunk_id", id);
        metadata.put("knowledge", knowledgeTag);
        metadata.put("source", "source-" + id + ".md");
        metadata.put("chunk_index", chunkIndex);
        return Document.builder().id(id).text(text).metadata(metadata).build();
    }
}
