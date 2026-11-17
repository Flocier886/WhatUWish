package com.flocier.domain.agent.service.armory.node.factory.element;

import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证旧 Flow Advisor 在复用共享检索内核后，仍投影原有 qa_* 阶段信息和 Prompt 上下文。
 */
public class RagAnswerAdvanceAdvisorCompatibilityTest {

    @Test
    public void shouldKeepLegacyQaMetadataAndInjectFinalDocumentsIntoPromptContext() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document vectorDocument = document("vector-1", "向量召回资料");
        Document keywordDocument = document("keyword-1", "关键词召回资料");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorDocument));

        RagAnswerAdvanceAdvisor advisor = new RagAnswerAdvanceAdvisor(
                vectorStore,
                SearchRequest.builder().topK(8).build(),
                (query, topK, context) -> List.of(keywordDocument),
                8,
                (query, context) -> "改写后的问题",
                (query, candidates, context) -> List.of(
                        new EnhancedRagRetriever.ScoredDocument(keywordDocument, 0.9d),
                        new EnhancedRagRetriever.ScoredDocument(vectorDocument, 0.8d)
                ),
                2
        );

        ChatClientRequest advised = advisor.before(ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(new UserMessage("原始问题")).build())
                .context(new LinkedHashMap<>())
                .build(), null);

        assertEquals("原始问题", advised.context().get("qa_original_query"));
        assertEquals("改写后的问题", advised.context().get("qa_rewritten_query"));
        assertEquals(1, documents(advised.context(), "qa_vector_retrieved_documents").size());
        assertEquals(1, documents(advised.context(), "qa_keyword_retrieved_documents").size());
        assertEquals(2, documents(advised.context(), "qa_coarse_rank_documents").size());
        assertEquals(2, documents(advised.context(), "qa_rerank_documents").size());
        assertEquals(List.of(keywordDocument, vectorDocument), documents(advised.context(), "qa_retrieved_documents"));
        assertTrue(advised.context().containsKey("qa_rerank_scores"));

        String userPrompt = advised.prompt().getUserMessage().getText();
        assertTrue(userPrompt.contains("Context information is below"));
        assertTrue(userPrompt.contains("the user that you can't answer the question"));
        String assistantContext = advised.prompt().getInstructions().get(1).getText();
        assertTrue(assistantContext.contains("关键词召回资料"));
        assertTrue(assistantContext.contains("向量召回资料"));
    }

    @SuppressWarnings("unchecked")
    private static List<Document> documents(Map<String, Object> context, String key) {
        return (List<Document>) context.get(key);
    }

    private static Document document(String id, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of(
                "chunk_id", id,
                "source", id + ".md"
        )).build();
    }
}
