package com.flocier.test.domain;

import com.flocier.domain.agent.adapter.port.IEventPublisher;
import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.event.SyncRagChunkToEsMessageEvent;
import com.flocier.domain.agent.model.vo.AiRagOrderVO;
import com.flocier.domain.agent.service.rag.RagService;
import com.flocier.domain.agent.service.rag.splitter.DocumentChunkSplitter;
import com.flocier.types.event.BaseEvent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RagServiceTest {

    @Mock
    private DocumentChunkSplitter documentChunkSplitter;
    @Mock
    private PgVectorStore vectorStore;
    @Mock
    private IAgentRepository repository;
    @Mock
    private SyncRagChunkToEsMessageEvent syncRagChunkToEsMessageEvent;
    @Mock
    private IEventPublisher eventPublisher;

    private RagService ragService;

    @Before
    public void setUp() {
        ragService = new RagService();
        ReflectionTestUtils.setField(ragService, "documentChunkSplitter", documentChunkSplitter);
        ReflectionTestUtils.setField(ragService, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(ragService, "repository", repository);
        ReflectionTestUtils.setField(ragService, "syncRagChunkToEsMessageEvent", syncRagChunkToEsMessageEvent);
        ReflectionTestUtils.setField(ragService, "eventPublisher", eventPublisher);
    }

    @Test
    public void test_storeRagFile_injectChunkIdAndPublishSyncEvent() {
        String chunkId1 = UUID.randomUUID().toString();
        String chunkId2 = UUID.randomUUID().toString();

        Document d1 = Document.builder()
                .id(chunkId1)
                .text("chunk-one")
                .metadata(new LinkedHashMap<>(Map.of("parent_document_id", "doc-001", "chunk_index", 0)))
                .build();
        Document d2 = Document.builder()
                .id(chunkId2)
                .text("chunk-two")
                .metadata(new LinkedHashMap<>(Map.of("parent_document_id", "doc-001", "chunk_index", 1)))
                .build();
        List<Document> docs = List.of(d1, d2);

        when(documentChunkSplitter.split(anyList())).thenReturn(docs);
        when(syncRagChunkToEsMessageEvent.topic()).thenReturn("ai.agent.sync.rag.es");

        AtomicReference<SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage> eventDataRef = new AtomicReference<>();
        when(syncRagChunkToEsMessageEvent.buildEventMessage(any())).thenAnswer(invocation -> {
            SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage data = invocation.getArgument(0);
            eventDataRef.set(data);
            return BaseEvent.EventMessage.<SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage>builder()
                    .id("1001")
                    .timestamp(new Date())
                    .data(data)
                    .build();
        });

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "kb_001.txt",
                "text/plain",
                "RAG 测试文档内容".getBytes(StandardCharsets.UTF_8)
        );

        ragService.storeRagFile("rag-test", "test_document", List.of(file));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).accept(docsCaptor.capture());
        List<Document> acceptedDocs = docsCaptor.getValue();
        assertEquals(2, acceptedDocs.size());
        assertEquals("test_document", acceptedDocs.get(0).getMetadata().get("knowledge"));
        assertEquals(chunkId1, acceptedDocs.get(0).getMetadata().get("chunk_id"));
        assertEquals(chunkId2, acceptedDocs.get(1).getMetadata().get("chunk_id"));

        verify(eventPublisher, times(1))
                .publish(eq("ai.agent.sync.rag.es"), any(BaseEvent.EventMessage.class));
        SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage eventData = eventDataRef.get();
        assertNotNull(eventData);
        assertEquals("rag-test", eventData.getRagName());
        assertEquals("test_document", eventData.getKnowledgeTag());
        assertEquals("kb_001.txt", eventData.getSourceFile());
        assertEquals(2, eventData.getChunks().size());
        assertEquals(chunkId1, eventData.getChunks().get(0).getChunkId());
        assertEquals("test_document", String.valueOf(eventData.getChunks().get(0).getMetadata().get("knowledge")));
        assertEquals(chunkId1, String.valueOf(eventData.getChunks().get(0).getMetadata().get("chunk_id")));
        assertTrue(eventData.getChunks().get(0).getMetadata().containsKey("chunk_index"));

        ArgumentCaptor<AiRagOrderVO> orderCaptor = ArgumentCaptor.forClass(AiRagOrderVO.class);
        verify(repository, times(1)).createTagOrder(orderCaptor.capture());
        assertEquals("rag-test", orderCaptor.getValue().getRagName());
        assertEquals("test_document", orderCaptor.getValue().getKnowledgeTag());
    }
}
