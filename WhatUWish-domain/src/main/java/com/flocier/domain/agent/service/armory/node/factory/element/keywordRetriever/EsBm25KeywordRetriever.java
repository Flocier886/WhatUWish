package com.flocier.domain.agent.service.armory.node.factory.element.keywordRetriever;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import com.flocier.domain.agent.service.armory.support.DefaultRagAdvanceAdvisorBeansProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Elasticsearch 的 BM25 关键词召回实现。
 * 目前相关数据写死在了对象中，在使用中存在限制，比如说同一时刻只能查同一个索引，如果遇到了多业务场景的话会受到限制，后续要用到时会进行相关拓展。
 *
 * <p>由 Spring 注册为 Bean；装配时通过 {@link DefaultRagAdvanceAdvisorBeansProvider} 按配置 code 注入。</p>
 * <p>默认查询索引：{@code rag_knowledge_v1}，查询字段：{@code content}，支持可选 knowledge_tag 过滤。</p>
 */
@Slf4j
@Component("EsBm25KeywordRetriever")
public class EsBm25KeywordRetriever implements EnhancedRagRetriever.KeywordRetriever {

    @Value("${bm25.es.base-url:http://192.168.100.128:9200}")
    private String baseUrl;

    @Value("${bm25.es.index:rag_knowledge_v1}")
    private String indexName;

    @Value("${bm25.es.content-field:content}")
    private String contentField;

    @Value("${bm25.es.knowledge-filter-field:knowledge_tag}")
    private String knowledgeFilterField;

    @Value("${bm25.es.timeout-ms:15000}")
    private int timeoutMs;

    private volatile HttpClient httpClient;

    @Override
    public List<Document> search(String query, int topK, Map<String, Object> context) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        try {
            JSONObject payload = buildPayload(query.trim(), topK, context);
            String uri = normalizeBaseUrl(this.baseUrl) + "/" + this.indexName + "/_search";

            HttpRequest request = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(uri))
                    .timeout(Duration.ofMillis(effectiveTimeoutMs()))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("[EsBm25KeywordRetriever] keyword recall fallback: non-2xx HTTP | uri={} | status={} | bodySnippet={}",
                        uri, response.statusCode(), snippet(response.body(), 800));
                markFallback(context, "non_2xx_response");
                return List.of();
            }

            List<Document> out = parseHits(response.body());
            if (context != null) {
                context.put("qa_keyword_retriever", this.getClass().getSimpleName());
                context.put("qa_keyword_retriever_index", this.indexName);
            }
            return out;
        } catch (Exception e) {
            log.warn("[EsBm25KeywordRetriever] keyword recall fallback: request failed | index={} | topK={}",
                    this.indexName, topK, e);
            markFallback(context, "request_failed");
            return List.of();
        }
    }

    private JSONObject buildPayload(String query, int topK, Map<String, Object> context) {
        JSONObject root = new JSONObject();
        root.put("size", topK);

        JSONObject bool = new JSONObject();
        JSONArray must = new JSONArray();
        JSONObject match = new JSONObject();
        JSONObject contentMatch = new JSONObject();
        contentMatch.put("query", query);
        contentMatch.put("operator", "or");
        match.put(this.contentField, contentMatch);
        must.add(Map.of("match", match));
        bool.put("must", must);

        String knowledgeTag = resolveKnowledgeFilter(context);
        if (StringUtils.hasText(knowledgeTag)) {
            JSONArray filter = new JSONArray();
            JSONObject term = new JSONObject();
            term.put(this.knowledgeFilterField, knowledgeTag.trim());
            filter.add(Map.of("term", term));
            bool.put("filter", filter);
        }

        root.put("query", Map.of("bool", bool));
        return root;
    }

    private static String resolveKnowledgeFilter(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        Object v1 = context.get("qa_keyword_filter");
        if (v1 != null && StringUtils.hasText(v1.toString())) {
            return v1.toString();
        }
        Object v2 = context.get("qa_knowledge_tag");
        if (v2 != null && StringUtils.hasText(v2.toString())) {
            return v2.toString();
        }
        return null;
    }

    private List<Document> parseHits(String body) {
        JSONObject root = JSON.parseObject(body);
        if (root == null) {
            return List.of();
        }
        JSONObject hitsObj = root.getJSONObject("hits");
        if (hitsObj == null) {
            return List.of();
        }
        JSONArray hits = hitsObj.getJSONArray("hits");
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<Document> out = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            JSONObject hit = hits.getJSONObject(i);
            if (hit == null) {
                continue;
            }
            JSONObject source = hit.getJSONObject("_source");
            if (source == null) {
                continue;
            }
            String content = source.getString(this.contentField);
            if (!StringUtils.hasText(content)) {
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            JSONObject originalMetadata = source.getJSONObject("metadata");
            if (originalMetadata != null && !originalMetadata.isEmpty()) {
                metadata.putAll(originalMetadata);
            }

            putIfPresent(metadata, "chunk_id", source.get("chunk_id"));
            putIfPresent(metadata, "knowledge_tag", source.get("knowledge_tag"));
            putIfPresent(metadata, "source_file", source.get("source_file"));
            putIfPresent(metadata, "doc_id", source.get("doc_id"));
            putIfPresent(metadata, "chunk_index", source.get("chunk_index"));
            putIfPresent(metadata, "total_chunks", source.get("total_chunks"));
            putIfPresent(metadata, "created_at", source.get("created_at"));

            putIfPresent(metadata, "es_index", hit.getString("_index"));
            putIfPresent(metadata, "es_id", hit.getString("_id"));
            metadata.put("es_bm25_score", hit.getDoubleValue("_score"));

            out.add(new Document(content, metadata));
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    /**
     * 当前 Retriever 保持旧有的“失败时返回空结果”契约，同时留下受控标记，
     * 供共享检索内核将真实降级投影给 Tool/运行记录，而不泄露 HTTP 细节。
     */
    private static void markFallback(Map<String, Object> context, String reason) {
        if (context == null) {
            return;
        }
        context.put("qa_keyword_fallback", true);
        context.put("qa_keyword_failure", reason);
    }

    private HttpClient httpClient() {
        HttpClient local = this.httpClient;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (this.httpClient != null) {
                return this.httpClient;
            }
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(effectiveTimeoutMs()))
                    .build();
            return Objects.requireNonNull(this.httpClient);
        }
    }

    private int effectiveTimeoutMs() {
        return this.timeoutMs <= 0 ? 15000 : this.timeoutMs;
    }

    private static String normalizeBaseUrl(String base) {
        if (!StringUtils.hasText(base)) {
            return "http://127.0.0.1:9200";
        }
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String snippet(String body, int maxLen) {
        if (!StringUtils.hasText(body)) {
            return "(empty)";
        }
        String b = body.replace('\r', ' ').replace('\n', ' ').trim();
        if (b.length() <= maxLen) {
            return b;
        }
        return b.substring(0, maxLen) + "...(truncated)";
    }
}
