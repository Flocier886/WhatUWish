package com.flocier.domain.agent.event;

import com.flocier.types.event.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * RAG 文档切片同步到 ES 的 MQ 事件定义。
 */
@Component
public class SyncRagChunkToEsMessageEvent extends BaseEvent<SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage> {

    @Value("${spring.rabbitmq.topic.sync_rag_es}")
    private String topic;

    @Override
    public EventMessage<SyncRagChunkToEsMessage> buildEventMessage(SyncRagChunkToEsMessage data) {
        return EventMessage.<SyncRagChunkToEsMessage>builder()
                .id(RandomStringUtils.randomNumeric(11))
                .timestamp(new Date())
                .data(data)
                .build();
    }

    @Override
    public String topic() {
        return this.topic;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SyncRagChunkToEsMessage {
        private String ragName;
        private String knowledgeTag;
        private String sourceFile;
        private List<ChunkPayload> chunks;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChunkPayload {
        private String chunkId;
        private String content;
        private Map<String, Object> metadata;
    }
}
