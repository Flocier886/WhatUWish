package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RagRetrievalToolTest {

    @Test
    public void shouldReturnControlledObservationAndStructuredEvidence() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document document = Document.builder().id("chunk-1")
                .text("Spring Boot 自动配置通过条件化配置减少手写 Bean 注册。")
                .metadata(new LinkedHashMap<>(Map.of("knowledge", "spring", "source", "boot-basics.md", "chunk_id", "chunk-1")))
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        RagRetrievalTool tool = new RagRetrievalTool("rag_retrieval_tool", "desc", Map.of(),
                new EnhancedRagRetriever(vectorStore, SearchRequest.builder().topK(5).build(), null, 0, null, null, 5), 5);

        ToolResultPayload result = (ToolResultPayload) tool.execute(request(Map.of(
                "query", "Spring Boot 自动配置为什么减少样板代码", "knowledgeTag", "spring", "topK", 3)), AgentContext.builder().build());

        assertTrue(result.isSuccess());
        assertTrue(result.getLlmObservation().contains("知识库增强检索完成"));
        assertTrue(result.getLlmObservation().contains("boot-basics.md"));
        assertTrue(result.getLlmObservation().contains("仅依据以上已召回资料"));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.getStructuredOutput();
        assertEquals(1, structured.get("finalHitCount"));
        assertFalse(result.getLlmObservation().contains("chunk-1"));
        assertFalse(result.getLlmObservation().contains("storageKey"));
    }

    @Test
    public void shouldDescribeNoHitAndVectorFailureWithoutFabricatingEvidence() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RagRetrievalTool tool = new RagRetrievalTool("rag_retrieval_tool", "desc", Map.of(),
                new EnhancedRagRetriever(vectorStore, SearchRequest.builder().topK(5).build(), null, 0, null, null, 5), 5);
        ToolResultPayload noHit = (ToolResultPayload) tool.execute(request(Map.of("query", "不存在的内部资料")), AgentContext.builder().build());
        assertTrue(noHit.isSuccess());
        assertTrue(noHit.getLlmObservation().contains("未检索到足够相关资料"));

        VectorStore unavailableStore = mock(VectorStore.class);
        when(unavailableStore.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("vector unavailable"));
        RagRetrievalTool unavailableTool = new RagRetrievalTool("rag_retrieval_tool", "desc", Map.of(),
                new EnhancedRagRetriever(unavailableStore, SearchRequest.builder().topK(5).build(), null, 0, null, null, 5), 5);
        ToolResultPayload unavailable = (ToolResultPayload) unavailableTool.execute(request(Map.of("query", "需要内部资料")), AgentContext.builder().build());
        assertFalse(unavailable.isSuccess());
        assertEquals("rag_vector_unavailable", unavailable.getErrorCode());
        assertTrue(unavailable.getLlmObservation().contains("未获得可信证据"));
    }

    private ToolInvokeRequest request(Map<String, Object> arguments) {
        return ToolInvokeRequest.builder().toolName("rag_retrieval_tool").toolCallId("call-rag")
                .arguments(arguments).build();
    }
}
