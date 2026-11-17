package com.flocier.test.domain;

import com.flocier.domain.agent.model.entity.ShortTermMemoryAppendCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryResultEntity;
import com.flocier.domain.agent.service.IMemoryService;
import com.flocier.types.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class AgentRepositoryMemorySummaryTest {

    @Autowired
    private IMemoryService memoryService;

    @Autowired
    private RedissonClient redissonClient;

    private String sessionId;
    private String recordsKey;
    private String summaryKey;
    private String roundKey;
    private String lockKey;

    @Before
    public void setUp() {
        sessionId = "memory-summary-test-" + UUID.randomUUID();
        recordsKey = Constants.MEMORY_RECORDS + sessionId;
        summaryKey = Constants.MEMORY_SUMMARY + sessionId;
        roundKey = Constants.MEMORY_ROUND + sessionId;
        lockKey = Constants.MEMORY_LOCK + sessionId;
        //clearSessionKeys();
    }

    @After
    public void tearDown() {
        //clearSessionKeys();
    }

    @Test
    public void test_appendAndQuery_memorySummaryExtraction_shouldBeTriggeredAndReturned() {
        log.info("开始测试摘要提取链路，sessionId:{}", sessionId);
        for (int i = 1; i <= 8; i++) {
            memoryService.appendShortTermMemory(ShortTermMemoryAppendCommandEntity.builder()
                    .sessionId(sessionId)
                    .userQuestion("问题-" + i)
                    .finalAnswer("回答-" + i)
                    .windowSize(8)
                    .build());
        }

        ShortTermMemoryQueryResultEntity result = memoryService.queryShortTermMemory(
                ShortTermMemoryQueryCommandEntity.builder()
                        .sessionId(sessionId)
                        .recentRounds(20)
                        .build()
        );

        assertNotNull(result);
        assertEquals(Long.valueOf(8L), result.getTotalRounds());
        assertEquals("压缩后应保留 floor(8 * 0.75)=6 条", 6, result.getRecentRecords().size());
        assertTrue("达到窗口上限后应产出摘要内容", result.getSummary() != null && !result.getSummary().trim().isEmpty());
        assertTrue("summary key 应真实写入 Redis", redissonClient.getBucket(summaryKey).isExists());
        assertEquals("round key 应累计到8", 8L, redissonClient.getAtomicLong(roundKey).get());
        log.info("摘要提取测试通过，summary:{}，recentRecords:{}，redisSummaryKey:{}",
                result.getSummary(), result.getRecentRecords().size(), summaryKey);
    }

    private void clearSessionKeys() {
        redissonClient.getKeys().delete(recordsKey, summaryKey, roundKey, lockKey);
    }
}
