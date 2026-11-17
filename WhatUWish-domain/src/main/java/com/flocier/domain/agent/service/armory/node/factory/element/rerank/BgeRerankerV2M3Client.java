package com.flocier.domain.agent.service.armory.node.factory.element.rerank;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 调用自托管 {@code wkao/bge-reranker-v2-m3} 容器的 HTTP Rerank API（OpenAI 兼容风格）。
 *
 * <p>固定绑定模型名：{@link #MODEL}</p>
 * <p>配置项由 app 层 {@code application-dev.yml} / {@code application-prod.yml}（或其它 profile）提供，例如：</p>
 * <ul>
 *   <li>{@code rerank.base-url} — 例如 {@code http://192.168.x.x:6006}</li>
 *   <li>{@code rerank.api-key} — Bearer Token，需与容器侧一致</li>
 *   <li>{@code rerank.max-candidates} — 单次 HTTP 请求送入 rerank 服务的最大候选数（用于控制延迟与负载；与 {@link EnhancedRagRetriever} 的最终保留条数无关）</li>
 * </ul>
 *
 * <p>由 Spring 注册为 Bean；装配时通过 {@link DefaultRagAdvanceAdvisorBeansProvider} 按配置 code 注入。</p>
 */
@Slf4j
@Component("BgeRerankerV2M3Client")
public class BgeRerankerV2M3Client implements EnhancedRagRetriever.Reranker {

    /** 与镜像 README 中请求的 model 字段一致 */
    private static final String MODEL = "bge-reranker-v2-m3";

    private static final String RERANK_PATH = "/v1/rerank";

    /** 每条文档参与打分的最大字符数 */
    private static final int DEFAULT_MAX_DOC_CHARS = 2500;

    /** HTTP 超时（毫秒） */
    private static final int DEFAULT_TIMEOUT_MS = 30000;

    /** 对应配置 {@code rerank.base-url} */
    @Value("${rerank.base-url}")
    private String baseUrl;

    /** 对应配置 {@code rerank.api-key}，HTTP Header {@code Authorization: Bearer ...} */
    @Value("${rerank.api-key}")
    private String apiKey;

    /**
     * 对应配置 {@code rerank.max-candidates}：单次请求送入 rerank API 的最大候选数（粗排可能很长，此处仅做技术服务上限；精排后取几条由 Advisor 的 rerankTopK 决定）。
     */
    @Value("${rerank.max-candidates}")
    private int maxCandidates = 48;

    private volatile HttpClient httpClient;

    public BgeRerankerV2M3Client() {
    }

    /**
     * 非 Spring 场景手动构造；{@code maxCandidates} 默认 48，与配置 {@code rerank.max-candidates} 缺省值一致。
     */
    public BgeRerankerV2M3Client(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxCandidates = 48;
    }

    @Override
    public List<EnhancedRagRetriever.ScoredDocument> rerank(String query, List<Document> candidates, Map<String, Object> context) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        String q = query == null ? "" : query.trim();
        if (!StringUtils.hasText(q)) {
            log.warn("[BgeRerankerV2M3Client] rerank fallback: blank query | candidates={}", candidates.size());
            return fallbackScores(candidates, context, "blank_query");
        }

        int cap = effectiveMaxCandidates();
        List<Document> slice = candidates.size() > cap
                ? new ArrayList<>(candidates.subList(0, cap))
                : new ArrayList<>(candidates);

        List<String> texts = new ArrayList<>(slice.size());
        for (Document doc : slice) {
            texts.add(truncate(doc == null ? "" : doc.getText(), DEFAULT_MAX_DOC_CHARS));
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("model", MODEL);
            payload.put("query", q);
            payload.put("documents", texts);

            String uri = normalizeBaseUrl(this.baseUrl) + RERANK_PATH;
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(uri))
                    .timeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
                    .header("Content-Type", "application/json; charset=UTF-8");

            if (StringUtils.hasText(this.apiKey)) {
                rb.header("Authorization", "Bearer " + this.apiKey.trim());
            }

            HttpRequest request = rb
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                log.warn("[BgeRerankerV2M3Client] rerank fallback: non-2xx HTTP | uri={} | status={} | bodySnippet={}",
                        uri, response.statusCode(), snippet(response.body(), 800));
                return fallbackScores(slice, context, "non_2xx_response");
            }

            JSONObject root = JSON.parseObject(response.body());
            if (root == null) {
                log.warn("[BgeRerankerV2M3Client] rerank fallback: JSON root null | uri={} | bodySnippet={}",
                        uri, snippet(response.body(), 800));
                return fallbackScores(slice, context, "empty_response");
            }

            JSONArray results = root.getJSONArray("results");
            if (results == null || results.isEmpty()) {
                log.warn("[BgeRerankerV2M3Client] rerank fallback: empty or missing results[] | uri={} | bodySnippet={}",
                        uri, snippet(response.body(), 800));
                return fallbackScores(slice, context, "empty_response");
            }

            List<EnhancedRagRetriever.ScoredDocument> out = new ArrayList<>(results.size());
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                int index = item.getIntValue("index");
                double score = item.getDoubleValue("relevance_score");
                if (index < 0 || index >= slice.size()) {
                    continue;
                }
                out.add(new EnhancedRagRetriever.ScoredDocument(slice.get(index), score));
            }

            if (out.isEmpty()) {
                log.warn("[BgeRerankerV2M3Client] rerank fallback: parsed results but no valid index/score pairs | uri={} | sliceSize={} | rawResultsSize={} | bodySnippet={}",
                        uri, slice.size(), results.size(), snippet(response.body(), 800));
                return fallbackScores(slice, context, "invalid_response");
            }

            if (context != null) {
                context.put("qa_rerank_client", this.getClass().getSimpleName());
                context.put("qa_rerank_model", MODEL);
            }

            return out;
        } catch (Exception e) {
            String uriForLog = normalizeBaseUrl(this.baseUrl) + RERANK_PATH;
            log.warn("[BgeRerankerV2M3Client] rerank fallback: request failed | uri={} | sliceSize={}",
                    uriForLog, slice.size(), e);
            return fallbackScores(slice, context, "request_failed");
        }
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
            if (!StringUtils.hasText(this.baseUrl)) {
                throw new IllegalStateException("rerank.base-url is required for BgeRerankerV2M3Client");
            }
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
                    .build();
            return Objects.requireNonNull(this.httpClient);
        }
    }

    private static List<EnhancedRagRetriever.ScoredDocument> fallbackScores(List<Document> slice,
                                                                              Map<String, Object> context,
                                                                              String reason) {
        if (context != null) {
            context.put("qa_rerank_fallback", true);
            context.put("qa_rerank_failure", reason);
        }
        List<EnhancedRagRetriever.ScoredDocument> list = new ArrayList<>(slice.size());
        for (Document d : slice) {
            list.add(new EnhancedRagRetriever.ScoredDocument(d, 0d));
        }
        return list;
    }

    private static String normalizeBaseUrl(String base) {
        if (!StringUtils.hasText(base)) {
            return "";
        }
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private int effectiveMaxCandidates() {
        return this.maxCandidates <= 0 ? 48 : this.maxCandidates;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    /** 日志用截断，避免刷屏 */
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
