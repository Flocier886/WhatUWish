package com.flocier.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
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
import java.util.Objects;

import static org.junit.Assert.assertTrue;

/**
 * 将 PostgreSQL pgvector 表（默认 vector_store_test）中的测试数据同步到 ES 索引（默认 rag_knowledge_v1）。
 *
 * <p>用途：先把测试知识库文档灌到 ES，支持 BM25 开发联调。</p>
 * <p>说明：该脚本是“测试脚本/手动执行工具”，不影响线上入库链路。</p>
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class PgVectorToEsSyncTest {

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @Value("${sync.es.base-url:http://192.168.100.128:9200}")
    private String esBaseUrl;

    @Value("${sync.es.index:rag_knowledge_v1}")
    private String esIndex;

    @Value("${sync.pgvector.table:vector_store_test}")
    private String sourceTable;

    @Value("${sync.batch-size:200}")
    private int batchSize;

    /**
     * 可选：限制本次最多同步多少条；<=0 表示不限（全量同步）。
     */
    @Value("${sync.max-rows:0}")
    private int maxRows;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 从 PostgreSQL 分页读取 id/content/metadata，按 ES bulk upsert 写入索引。
     * 执行前请确保：
     * 1) ES 索引 rag_knowledge_v1 已创建（mapping 含 content/knowledge_tag/source_file 等字段）
     * 2) ES 与 PostgreSQL 网络可达
     */
    @Test
    public void test_syncPgVectorTableToEsBulk() throws Exception {
        String base = normalizeBaseUrl(esBaseUrl);
        String bulkUrl = base + "/_bulk";

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();

        int pageSize = batchSize <= 0 ? 200 : batchSize;
        int offset = 0;
        int synced = 0;
        int round = 0;
        int limit = maxRows <= 0 ? Integer.MAX_VALUE : maxRows;

        log.info("[PG->ES] start sync, table={}, index={}, esBaseUrl={}, batchSize={}, maxRows={}",
                sourceTable, esIndex, base, pageSize, maxRows);

        while (synced < limit) {
            int currentFetchSize = Math.min(pageSize, limit - synced);
            List<Map<String, Object>> rows = loadRows(offset, currentFetchSize);
            if (rows.isEmpty()) {
                break;
            }

            String payload = buildBulkPayload(rows);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(bulkUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/x-ndjson; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertTrue("ES bulk HTTP 非 2xx，status=" + response.statusCode() + ", body=" + snippet(response.body()),
                    response.statusCode() / 100 == 2);

            JSONObject root = JSON.parseObject(response.body());
            boolean hasErrors = root != null && root.getBooleanValue("errors");
            if (hasErrors) {
                throw new IllegalStateException("ES bulk 返回 errors=true，body=" + snippet(response.body()));
            }

            round++;
            synced += rows.size();
            offset += rows.size();
            log.info("[PG->ES] round={} syncedThisRound={} totalSynced={}", round, rows.size(), synced);
        }

        log.info("[PG->ES] finished sync, totalSynced={}, table={}, index={}", synced, sourceTable, esIndex);
        assertTrue("未同步到任何数据，请检查源表是否有数据", synced > 0);
    }

    private List<Map<String, Object>> loadRows(int offset, int pageSize) {
        String sql = "SELECT id, content, metadata FROM " + sourceTable + " ORDER BY id LIMIT ? OFFSET ?";
        return pgVectorJdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id"));
            row.put("content", rs.getString("content"));
            row.put("metadata", rs.getObject("metadata"));
            return row;
        }, pageSize, offset);
    }

    private String buildBulkPayload(List<Map<String, Object>> rows) {
        List<String> lines = new ArrayList<>(rows.size() * 2);
        String createdAt = Instant.now().toString();

        for (Map<String, Object> row : rows) {
            String chunkId = String.valueOf(row.get("id"));
            JSONObject metadata = parseMetadata(row.get("metadata"));
            String content = Objects.toString(row.get("content"), "");

            Map<String, Object> action = Map.of("index", Map.of("_index", esIndex, "_id", chunkId));
            lines.add(JSON.toJSONString(action));

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("chunk_id", chunkId);
            source.put("content", content);
            source.put("created_at", createdAt);

            Object knowledge = metadata.get("knowledge");
            if (knowledge != null) {
                source.put("knowledge_tag", String.valueOf(knowledge));
            }
            Object sourceFile = metadata.get("source");
            if (sourceFile != null) {
                source.put("source_file", String.valueOf(sourceFile));
            }
            Object parentDocumentId = metadata.get("parent_document_id");
            if (parentDocumentId != null) {
                source.put("doc_id", String.valueOf(parentDocumentId));
            }
            Object chunkIndex = metadata.get("chunk_index");
            if (chunkIndex != null) {
                source.put("chunk_index", chunkIndex);
            }
            Object totalChunks = metadata.get("total_chunks");
            if (totalChunks != null) {
                source.put("total_chunks", totalChunks);
            }

            // 保留原 metadata，便于后续回溯排查
            source.put("metadata", metadata);
            lines.add(JSON.toJSONString(source));
        }

        return String.join("\n", lines) + "\n";
    }

    private static JSONObject parseMetadata(Object raw) {
        if (raw == null) {
            return new JSONObject();
        }
        if (raw instanceof JSONObject jsonObject) {
            return jsonObject;
        }
        String text = String.valueOf(raw);
        if (!StringUtils.hasText(text)) {
            return new JSONObject();
        }
        try {
            JSONObject obj = JSON.parseObject(text);
            return obj == null ? new JSONObject() : obj;
        } catch (Exception ignore) {
            return new JSONObject();
        }
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
        String text = body.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 600 ? text.substring(0, 600) + "...(truncated)" : text;
    }
}
