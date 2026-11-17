package com.flocier.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flocier.domain.agent.service.armory.node.factory.element.keywordRetriever.EsBm25KeywordRetriever;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 对 {@link EsBm25KeywordRetriever} 的集成测试：需本机可访问 Elasticsearch（默认来自 application-*.yml 的 bm25.es.*）。
 *
 * <p>测试策略：</p>
 * <ul>
 *   <li>每个用例先写入可控测试文档（独特关键词），再调用 retriever.search() 校验。</li>
 *   <li>验证点包括：命中返回、空 query 兜底、knowledge_tag 过滤。</li>
 * </ul>
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class EsBm25KeywordRetrieverTest {

    @Autowired
    private Environment environment;

    private EsBm25KeywordRetriever retriever;
    private HttpClient httpClient;
    private String esBaseUrl;
    private String esIndex;
    private String contentField;

    @Before
    public void setUp() {
        retriever = new EsBm25KeywordRetriever();
        esBaseUrl = normalizeBaseUrl(environment.getProperty("bm25.es.base-url", "http://192.168.100.128:9200"));
        esIndex = environment.getProperty("bm25.es.index", "rag_knowledge_v1");
        contentField = environment.getProperty("bm25.es.content-field", "content");
        String knowledgeField = environment.getProperty("bm25.es.knowledge-filter-field", "knowledge_tag");
        int timeoutMs = environment.getProperty("bm25.es.timeout-ms", Integer.class, 15000);

        ReflectionTestUtils.setField(retriever, "baseUrl", esBaseUrl);
        ReflectionTestUtils.setField(retriever, "indexName", esIndex);
        ReflectionTestUtils.setField(retriever, "contentField", contentField);
        ReflectionTestUtils.setField(retriever, "knowledgeFilterField", knowledgeField);
        ReflectionTestUtils.setField(retriever, "timeoutMs", timeoutMs);

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("BM25 测试配置: baseUrl={}, index={}, contentField={}, knowledgeField={}",
                esBaseUrl, esIndex, contentField, knowledgeField);
    }

    @Test
    public void test_search_hitsFixtureDocument() throws Exception {
        String uniqueKeyword = "BM25_UNIQUE_" + UUID.randomUUID().toString().replace("-", "");
        String tag = "bm25_test_suite";
        String docId = "bm25-fixture-" + UUID.randomUUID();
        String content = "这是BM25测试专用文档，关键词：" + uniqueKeyword + "，用于验证关键词召回。";

        indexFixtureDocument(docId, tag, content);
        refreshIndex();

        Map<String, Object> context = new HashMap<>();
        context.put("qa_knowledge_tag", tag);
        List<Document> hits = retriever.search(uniqueKeyword, 5, context);

        assertFalse("应命中刚写入的 fixture 文档", hits.isEmpty());
        String topText = hits.get(0).getText();
        assertTrue("Top1 应包含唯一关键词", StringUtils.hasText(topText) && topText.contains(uniqueKeyword));

        log.info("BM25 命中测试通过，keyword={}, topTextSnippet={}",
                uniqueKeyword, topText.length() > 120 ? topText.substring(0, 120) + "..." : topText);
    }

    @Test
    public void test_search_withKnowledgeFilter_onlyReturnsTaggedDocs() throws Exception {
        String sharedKeyword = "BM25_FILTER_KEY_" + UUID.randomUUID().toString().replace("-", "");
        String keepTag = "bm25_keep";
        String otherTag = "bm25_other";

        indexFixtureDocument("bm25-keep-" + UUID.randomUUID(), keepTag,
                "仅保留标签文档，关键词：" + sharedKeyword + "。");
        indexFixtureDocument("bm25-other-" + UUID.randomUUID(), otherTag,
                "其他标签文档，关键词：" + sharedKeyword + "。");
        refreshIndex();

        Map<String, Object> context = new HashMap<>();
        context.put("qa_knowledge_tag", keepTag);
        List<Document> hits = retriever.search(sharedKeyword, 10, context);

        assertFalse("带 knowledge_tag 过滤时应有命中", hits.isEmpty());
        for (Document d : hits) {
            Object tag = d.getMetadata().get("knowledge_tag");
            assertEquals("过滤后仅应返回目标 tag 的文档", keepTag, String.valueOf(tag));
        }
    }

    @Test
    public void test_search_blankQuery_returnsEmpty() {
        List<Document> hits = retriever.search("   ", 5, new HashMap<>());
        assertTrue(hits.isEmpty());
    }

    private void indexFixtureDocument(String docId, String knowledgeTag, String content) throws Exception {
        JSONObject source = new JSONObject();
        source.put("chunk_id", docId);
        source.put("knowledge_tag", knowledgeTag);
        source.put("source_file", "bm25-test-fixture.txt");
        source.put(contentField, content);
        source.put("created_at", Instant.now().toString());

        String url = esBaseUrl + "/" + esIndex + "/_doc/" + docId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(source.toJSONString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertTrue("fixture 写入 ES 失败，status=" + response.statusCode() + ", body=" + snippet(response.body()),
                response.statusCode() / 100 == 2);
        JSONObject root = JSON.parseObject(response.body());
        String result = root == null ? null : root.getString("result");
        assertTrue("fixture 写入结果异常，result=" + result,
                "created".equals(result) || "updated".equals(result));
    }

    private void refreshIndex() throws Exception {
        String url = esBaseUrl + "/" + esIndex + "/_refresh";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue("refresh index 失败，status=" + response.statusCode() + ", body=" + snippet(response.body()),
                response.statusCode() / 100 == 2);
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
        return b.length() > 500 ? b.substring(0, 500) + "...(truncated)" : b;
    }
}
