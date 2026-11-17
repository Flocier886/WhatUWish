package com.flocier.trigger.listener;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.agent.event.SyncRagChunkToEsMessageEvent;
import com.flocier.types.event.BaseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SyncRagChunkToEsListener {

    @Value("${spring.rabbitmq.topic.sync_rag_es}")
    private String topic;

    @Value("${sync.es.base-url:http://192.168.100.128:9200}")
    private String esBaseUrl;

    @Value("${sync.es.index:rag_knowledge_v1}")
    private String esIndex;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @RabbitListener(queuesToDeclare = @Queue(value = "${spring.rabbitmq.topic.sync_rag_es}"))
    public void listener(String message) {
        try {
            log.info("监听RAG切片同步消息 topic:{} message:{}", topic, message);
            BaseEvent.EventMessage<SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage> eventMessage =
                    JSON.parseObject(message,
                            new TypeReference<BaseEvent.EventMessage<SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage>>() {
                            }.getType());

            if (eventMessage == null || eventMessage.getData() == null) {
                log.warn("RAG切片同步消息为空，忽略消费 topic:{} message:{}", topic, message);
                return;
            }
            syncToEs(eventMessage.getData());
        } catch (Exception e) {
            log.error("监听RAG切片同步消息，消费失败 topic:{} message:{}", topic, message, e);
            throw new RuntimeException(e);
        }
    }

    private void syncToEs(SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage data) throws Exception {
        List<SyncRagChunkToEsMessageEvent.ChunkPayload> chunks = data.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            log.info("RAG切片同步消息无分片，跳过 ragName:{} sourceFile:{}", data.getRagName(), data.getSourceFile());
            return;
        }

        String payload = buildBulkPayload(data, chunks);
        String uri = normalizeBaseUrl(this.esBaseUrl) + "/_bulk";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-ndjson; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("ES bulk HTTP 非2xx, status=" + response.statusCode() + ", body=" + snippet(response.body()));
        }
        JSONObject root = JSON.parseObject(response.body());
        boolean errors = root != null && root.getBooleanValue("errors");
        if (errors) {
            throw new IllegalStateException("ES bulk 返回 errors=true, body=" + snippet(response.body()));
        }

        log.info("RAG切片同步ES成功 index:{} ragName:{} sourceFile:{} chunks:{}",
                this.esIndex, data.getRagName(), data.getSourceFile(), chunks.size());
    }

    private String buildBulkPayload(SyncRagChunkToEsMessageEvent.SyncRagChunkToEsMessage data,
                                    List<SyncRagChunkToEsMessageEvent.ChunkPayload> chunks) {
        List<String> lines = new ArrayList<>(chunks.size() * 2);
        String now = Instant.now().toString();

        for (SyncRagChunkToEsMessageEvent.ChunkPayload chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getChunkId())) {
                continue;
            }
            String chunkId = chunk.getChunkId().trim();

            Map<String, Object> action = Map.of("index", Map.of("_index", this.esIndex, "_id", chunkId));
            lines.add(JSON.toJSONString(action));

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("chunk_id", chunkId);
            source.put("content", chunk.getContent());
            source.put("knowledge_tag", data.getKnowledgeTag());
            source.put("source_file", data.getSourceFile());
            source.put("created_at", now);

            Map<String, Object> metadata = chunk.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chunk.getMetadata());
            if (!metadata.containsKey("chunk_id")) {
                metadata.put("chunk_id", chunkId);
            }
            if (!metadata.containsKey("knowledge") && StringUtils.hasText(data.getKnowledgeTag())) {
                metadata.put("knowledge", data.getKnowledgeTag());
            }
            if (!metadata.containsKey("source") && StringUtils.hasText(data.getSourceFile())) {
                metadata.put("source", data.getSourceFile());
            }
            source.put("metadata", metadata);

            // 同步一些常用字段，便于查询过滤
            if (metadata.get("parent_document_id") != null) {
                source.put("doc_id", String.valueOf(metadata.get("parent_document_id")));
            }
            if (metadata.get("chunk_index") != null) {
                source.put("chunk_index", metadata.get("chunk_index"));
            }
            if (metadata.get("total_chunks") != null) {
                source.put("total_chunks", metadata.get("total_chunks"));
            }

            lines.add(JSON.toJSONString(source));
        }

        return String.join("\n", lines) + "\n";
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String b = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "http://127.0.0.1:9200";
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String snippet(String body) {
        if (!StringUtils.hasText(body)) {
            return "(empty)";
        }
        String b = body.replace('\r', ' ').replace('\n', ' ').trim();
        return b.length() > 800 ? b.substring(0, 800) + "...(truncated)" : b;
    }
}
