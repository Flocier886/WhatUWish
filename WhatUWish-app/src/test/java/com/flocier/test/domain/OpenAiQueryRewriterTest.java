package com.flocier.test.domain;

import com.flocier.domain.agent.service.armory.node.factory.element.queryRewriter.OpenAiQueryRewriter;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 对 {@link OpenAiQueryRewriter} 的集成测试：依赖 {@code spring.ai.openai.base-url} / {@code api-key}，
 * 需本机能访问配置的 OpenAI 兼容网关（与 {@code application-dev.yml} 等一致）。
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class OpenAiQueryRewriterTest {

    @Autowired
    private Environment environment;

    private OpenAiQueryRewriter rewriter;

    @Before
    public void setUp() {
        String baseUrl = environment.getRequiredProperty("spring.ai.openai.base-url");
        String apiKey = environment.getRequiredProperty("spring.ai.openai.api-key");
        rewriter = new OpenAiQueryRewriter(baseUrl, apiKey);
        log.info("query rewrite 测试 spring.ai.openai.base-url={}", baseUrl);
    }

    @Test
    public void test_rewrite_redisQuestion_preservesIntentAndKeyword() {
        String original = "请问 Redis 主要是做什么用的呀？";
        Map<String, Object> ctx = new HashMap<>();
        String out = rewriter.rewrite(original, ctx);

        assertTrue("改写结果应为非空单行（需可用的 Chat API）", StringUtils.hasText(out));
        assertFalse("不应包含明显换行说明", out.contains("\n"));
        assertTrue("应保留 Redis 实体便于检索", out.toLowerCase().contains("redis"));
        assertEquals(OpenAiQueryRewriter.class.getSimpleName(), ctx.get("qa_query_rewriter"));

        log.info("query rewrite: '{}' -> '{}'", original, out);
    }

    @Test
    public void test_rewrite_null_returnsNull() {
        assertNull(rewriter.rewrite(null, new HashMap<>()));
    }

    @Test
    public void test_rewrite_blank_returnsBlank() {
        assertEquals("", rewriter.rewrite("", new HashMap<>()));
    }

    @Test
    public void test_rewrite_whitespaceOnly_returnsAsIs() {
        String in = "   ";
        assertEquals(in, rewriter.rewrite(in, new HashMap<>()));
    }

    @Test
    public void test_rewrite_contextNull_doesNotThrow() {
        String original = "Spring Boot 如何配置数据源";
        String out = rewriter.rewrite(original, null);
        assertNotNull(out);
        assertTrue(StringUtils.hasText(out));
        log.info("query rewrite (null context): '{}' -> '{}'", original, out);
    }
}
