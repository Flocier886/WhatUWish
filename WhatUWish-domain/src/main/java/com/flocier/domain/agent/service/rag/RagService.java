package com.flocier.domain.agent.service.rag;

import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.adapter.port.IEventPublisher;
import com.flocier.domain.agent.event.SyncRagChunkToEsMessageEvent;
import com.flocier.domain.agent.model.vo.AiRagOrderVO;
import com.flocier.domain.agent.service.IRagService;
import com.flocier.domain.agent.service.rag.splitter.DocumentChunkSplitter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagService implements IRagService {
    @Resource
    private DocumentChunkSplitter documentChunkSplitter;

    @Resource
    private PgVectorStore vectorStore;

    @Resource
    private IAgentRepository repository;
    @Resource
    private SyncRagChunkToEsMessageEvent syncRagChunkToEsMessageEvent;
    @Resource
    private IEventPublisher eventPublisher;

    @Override
    public void storeRagFile(String name, String tag, List<MultipartFile> files) {
        for (MultipartFile file : files) {
            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            List<Document> documentList = documentChunkSplitter.split(documentReader.get());

            // 添加知识库标签，并把框架生成的 Document ID 落到 metadata.chunk_id
            // 这样向量库与后续 ES 同步可以共享同一个 chunk 标识，便于 RRF 合并去重。
            documentList.forEach(doc -> {
                doc.getMetadata().put("knowledge", tag);
                if (doc.getId() != null) {
                    doc.getMetadata().put("chunk_id", doc.getId());
                }
            });

            // 存储知识库文件
            vectorStore.accept(documentList);
            // 发送异步消息，解耦 ES 同步链路（失败由消费端重试/补偿）
            publishRagChunkSyncEvent(name, tag, file, documentList);

            // 存储到数据库
            AiRagOrderVO aiRagOrderVO = new AiRagOrderVO();
            aiRagOrderVO.setRagName(name);
            aiRagOrderVO.setKnowledgeTag(tag);
            repository.createTagOrder(aiRagOrderVO);
        }
    }

    private void publishRagChunkSyncEvent(String ragName, String knowledgeTag, MultipartFile file, List<Document> documentList) {
        List<SyncRagChunkToEsMessageEvent.ChunkPayload> chunks = documentList.stream()
                .map(doc -> SyncRagChunkToEsMessageEvent.ChunkPayload.builder()
                        .chunkId(doc.getId())
                        .content(doc.getText())
                        .metadata(copyMetadata(doc.getMetadata()))
                        .build())
                .collect(Collectors.toList());

        SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage data = SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage.builder()
                .ragName(ragName)
                .knowledgeTag(knowledgeTag)
                .sourceFile(file == null ? null : file.getOriginalFilename())
                .chunks(chunks)
                .build();

        eventPublisher.publish(syncRagChunkToEsMessageEvent.topic(), syncRagChunkToEsMessageEvent.buildEventMessage(data));
        log.info("RAG 文档切片同步事件已发布 topic:{} ragName:{} sourceFile:{} chunks:{}",
                syncRagChunkToEsMessageEvent.topic(), ragName, file == null ? null : file.getOriginalFilename(), chunks.size());
    }

    private static Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return metadata.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Objects.toString(e.getValue(), null),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

}
