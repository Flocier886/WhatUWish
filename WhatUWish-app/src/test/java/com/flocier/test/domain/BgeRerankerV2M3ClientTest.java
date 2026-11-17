package com.flocier.test.domain;

import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import com.flocier.domain.agent.service.armory.node.factory.element.rerank.BgeRerankerV2M3Client;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对 {@link BgeRerankerV2M3Client} 的集成测试：需能访问 application-dev/prod 中配置的 {@code rerank.base-url} 对应容器。
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class BgeRerankerV2M3ClientTest {

    @Autowired
    private Environment environment;

    private BgeRerankerV2M3Client reranker;

    @Before
    public void setUp() {
        reranker = new BgeRerankerV2M3Client();
        String baseUrl = environment.getProperty("rerank.base-url");
        String apiKey = environment.getProperty("rerank.api-key");
        Integer maxCandidates = environment.getProperty("rerank.max-candidates", Integer.class, 48);

        ReflectionTestUtils.setField(reranker, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(reranker, "apiKey", apiKey == null ? "" : apiKey);
        ReflectionTestUtils.setField(reranker, "maxCandidates", maxCandidates);

        log.info("rerank 测试配置 base-url={}, max-candidates={}", baseUrl, maxCandidates);
    }

    @Test
    public void test_rerank_redisQuery_mostRelevantFirst() {
        String query = "Redis是什么";
        List<Document> candidates = new ArrayList<>();
        candidates.add(doc("Redis是内存数据库"));
        candidates.add(doc("MySQL是关系型数据库"));
        candidates.add(doc("RabbitMQ是消息队列"));

        List<EnhancedRagRetriever.ScoredDocument> scored =
                reranker.rerank(query, candidates, new HashMap<>());

        assertFalse("需要可连通的 rerank 服务，否则列表为空或全为降级分数", scored.isEmpty());
        assertEquals(3, scored.size());

        EnhancedRagRetriever.ScoredDocument top = scored.get(0);
        assertTrue("最高分应对应 Redis 文档", top.getScore() > 0.5);
        assertEquals("Redis是内存数据库", top.getDocument().getText());

        log.info("rerank 结果: first score={}, text={}", top.getScore(), top.getDocument().getText());
    }

    @Test
    public void test_rerank_emptyCandidates_returnsEmpty() {
        List<EnhancedRagRetriever.ScoredDocument> scored =
                reranker.rerank("任意问题", List.of(), new HashMap<>());
        assertTrue(scored.isEmpty());
    }

    @Test
    public void test_rerank_blankQuery_returnsFallbackOrder() {
        List<Document> candidates = List.of(doc("a"), doc("b"));
        List<EnhancedRagRetriever.ScoredDocument> scored =
                reranker.rerank("   ", candidates, new HashMap<>());
        assertEquals(2, scored.size());
        assertEquals(0d, scored.get(0).getScore(), 0d);
        assertEquals(0d, scored.get(1).getScore(), 0d);
    }

    private static Document doc(String text) {
        Document d = mock(Document.class);
        when(d.getText()).thenReturn(text);
        return d;
    }
}
