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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.redisson.api.RedissonClient;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class MemoryServiceTest {

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
        sessionId = "memory-test-" + UUID.randomUUID();
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
    public void test_appendShortTermMemory_shouldWriteRealRedisData() {
        ShortTermMemoryAppendCommandEntity command = ShortTermMemoryAppendCommandEntity.builder()
                .sessionId(sessionId)
                .userQuestion("什么是 RAG")
                .finalAnswer("RAG 是检索增强生成方案")
                .windowSize(10)
                .build();
        log.info("开始真实写入测试，sessionId:{}", sessionId);

        memoryService.appendShortTermMemory(command);

        long round = redissonClient.getAtomicLong(roundKey).get();
        int dequeSize = redissonClient.<Object>getDeque(recordsKey).size();
        assertEquals(1L, round);
        assertEquals(1, dequeSize);
        assertTrue(redissonClient.getKeys().countExists(recordsKey, roundKey) >= 2);
        log.info("写入完成，round:{} dequeSize:{} keys:{} {}", round, dequeSize, recordsKey, roundKey);
    }

    @Test
    public void test_queryShortTermMemory_shouldReadBackRealRedisData() {
        memoryService.appendShortTermMemory(ShortTermMemoryAppendCommandEntity.builder()
                .sessionId(sessionId)
                .userQuestion("问题1")
                .finalAnswer("回答1")
                .windowSize(10)
                .build());
        memoryService.appendShortTermMemory(ShortTermMemoryAppendCommandEntity.builder()
                .sessionId(sessionId)
                .userQuestion("问题2")
                .finalAnswer("回答2")
                .windowSize(10)
                .build());
        memoryService.appendShortTermMemory(ShortTermMemoryAppendCommandEntity.builder()
                .sessionId(sessionId)
                .userQuestion("问题3")
                .finalAnswer("回答3")
                .windowSize(10)
                .build());
        log.info("开始真实查询测试，sessionId:{}", sessionId);

        ShortTermMemoryQueryResultEntity result = memoryService.queryShortTermMemory(
                ShortTermMemoryQueryCommandEntity.builder()
                        .sessionId(sessionId)
                        .recentRounds(2)
                        .build());

        assertNotNull(result);
        assertEquals(sessionId, result.getSessionId());
        assertEquals(Long.valueOf(3L), result.getTotalRounds());
        assertEquals(2, result.getRecentRecords().size());
        assertEquals("问题2", result.getRecentRecords().get(0).getUserQuestion());
        assertEquals("问题3", result.getRecentRecords().get(1).getUserQuestion());
        assertNotNull(result.getSummary());
        log.info("真实查询完成，totalRounds:{} summary:{} recent:{}",
                result.getTotalRounds(), result.getSummary(), result.getRecentRecords().size());
    }

    private void clearSessionKeys() {
        redissonClient.getKeys().delete(recordsKey, summaryKey, roundKey, lockKey);
    }
}
