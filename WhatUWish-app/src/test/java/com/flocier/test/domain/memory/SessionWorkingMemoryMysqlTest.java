package com.flocier.test.domain.memory;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryProjector;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.infrastructure.adapter.repository.MysqlSessionWorkingMemoryRepository;
import com.flocier.infrastructure.dao.IWorkingMemoryCompactionDao;
import com.flocier.infrastructure.dao.IWorkingMemoryMessageDao;
import com.flocier.infrastructure.dao.IWorkingMemoryTurnDao;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = SessionWorkingMemoryMysqlTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
public class SessionWorkingMemoryMysqlTest {
    @jakarta.annotation.Resource private SessionWorkingMemoryRepository repository;
    @jakarta.annotation.Resource private IWorkingMemoryTurnDao turnDao;

    @Test
    public void readyProjectionSurvivesRepositoryCallsAndFailedReplaceRollsBack() {
        String suffix = UUID.randomUUID().toString();
        String sessionId = "wm-test-" + suffix;
        String firstRequest = "wm-first-" + suffix;
        String compactRequest = "wm-compact-" + suffix;
        List<LlmMessage> original = List.of(message(LlmMessageRole.USER, "question"),
                message(LlmMessageRole.ASSISTANT, "answer"));

        Assert.assertTrue(repository.persistTurn(sessionId, firstRequest, suffix, original));
        Assert.assertFalse(repository.persistTurn(sessionId, firstRequest, suffix, original));
        Assert.assertEquals(original.size(), repository.loadReadyMessages(sessionId, "next").size());
        Assert.assertTrue(repository.loadReadyMessages(sessionId, firstRequest).isEmpty());

        Assert.assertTrue(repository.replaceReadyProjection(sessionId, compactRequest,
                List.of(message(LlmMessageRole.USER, "summary"))));
        Assert.assertEquals(2, turnDao.selectByRequestId(firstRequest).getStatus().intValue());
        Assert.assertEquals("summary", repository.loadReadyMessages(sessionId, "next").get(0).getContent());

        String invalidRequest = "wm-invalid-" + suffix;
        LlmMessage invalid = LlmMessage.builder().role(LlmMessageRole.TOOL)
                .toolCallId("x".repeat(200)).content("bad").build();
        try {
            repository.replaceReadyProjection(sessionId, invalidRequest,
                    List.of(message(LlmMessageRole.USER, "still valid"), invalid));
            Assert.fail("Expected the overlong toolCallId to reject the replacement");
        } catch (org.springframework.dao.DataAccessException expected) {
            Assert.assertEquals(1, turnDao.selectByRequestId(compactRequest).getStatus().intValue());
            Assert.assertNull(turnDao.selectByRequestId(invalidRequest));
            Assert.assertEquals("summary", repository.loadReadyMessages(sessionId, "next").get(0).getContent());
        }
    }

    @Test
    public void incrementalMessagesSurviveReloadAndCompactionInvalidatesBuildingAtomically() {
        String suffix = UUID.randomUUID().toString();
        String session = "wm-incremental-" + suffix;
        String request = "wm-run-" + suffix;
        String run = suffix;
        repository.appendMessage(session, request, run, 0, message(LlmMessageRole.USER, "question"));
        Assert.assertEquals(0, turnDao.selectByRequestId(request).getStatus().intValue());
        Assert.assertEquals("question", repository.loadReadyMessages(session, "later").get(0).getContent());
        repository.appendMessage(session, request, run, 0, message(LlmMessageRole.USER, "question"));
        try {
            repository.appendMessage(session, request, run, 0, message(LlmMessageRole.USER, "other"));
            Assert.fail("conflicting message should fail");
        } catch (IllegalStateException expected) {
            Assert.assertEquals(1, turnDao.selectByRequestId(request).getMessageCount().intValue());
        }
        String compact = "wm-compacted-" + suffix;
        Assert.assertTrue(repository.replaceReadyProjection(session, compact,
                List.of(message(LlmMessageRole.USER, "summary"))));
        Assert.assertEquals(2, turnDao.selectByRequestId(request).getStatus().intValue());
        String nextSegment = "wm-segment-" + suffix;
        repository.appendMessage(session, nextSegment, run, 0, message(LlmMessageRole.ASSISTANT, "answer"));
        repository.completeSegment(nextSegment);
        Assert.assertEquals(1, turnDao.selectByRequestId(nextSegment).getStatus().intValue());
        Assert.assertEquals(List.of("summary", "answer"), repository.loadReadyMessages(session, "later").stream()
                .map(LlmMessage::getContent).toList());
    }

    @Test
    public void failedCompactionRestoresReadyAndBuildingInOneTransaction() {
        String suffix = UUID.randomUUID().toString();
        String session = "wm-rollback-" + suffix;
        String ready = "wm-ready-" + suffix;
        String building = "wm-building-" + suffix;
        String failed = "wm-failed-" + suffix;
        Assert.assertTrue(repository.persistTurn(session, ready, suffix,
                List.of(message(LlmMessageRole.USER, "old task"))));
        repository.appendMessage(session, building, suffix, 0,
                message(LlmMessageRole.USER, "current task"));

        LlmMessage invalid = LlmMessage.builder().role(LlmMessageRole.TOOL)
                .toolCallId("x".repeat(200)).content("bad").build();
        try {
            repository.replaceReadyProjection(session, failed,
                    List.of(message(LlmMessageRole.USER, "summary"), invalid));
            Assert.fail("Expected the invalid message to roll back the whole replacement");
        } catch (org.springframework.dao.DataAccessException expected) {
            Assert.assertEquals(1, turnDao.selectByRequestId(ready).getStatus().intValue());
            Assert.assertEquals(0, turnDao.selectByRequestId(building).getStatus().intValue());
            Assert.assertNull(turnDao.selectByRequestId(failed));
            Assert.assertEquals(List.of("old task", "current task"),
                    repository.loadReadyMessages(session, "later").stream()
                            .map(LlmMessage::getContent).toList());
        }
    }

    private LlmMessage message(LlmMessageRole role, String content) {
        return LlmMessage.builder().role(role).content(content).build();
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackages = "com.flocier.infrastructure.dao", sqlSessionFactoryRef = "workingMemorySqlSessionFactory")
    static class Config {
        @Bean DataSource workingMemoryDataSource(org.springframework.core.env.Environment environment) {
            return DataSourceBuilder.create()
                    .driverClassName(environment.getRequiredProperty("spring.datasource.mysql.driver-class-name"))
                    .url(environment.getRequiredProperty("spring.datasource.mysql.url"))
                    .username(environment.getRequiredProperty("spring.datasource.mysql.username"))
                    .password(environment.getRequiredProperty("spring.datasource.mysql.password"))
                    .build();
        }

        @Bean SqlSessionFactory workingMemorySqlSessionFactory(DataSource workingMemoryDataSource) throws Exception {
            SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
            bean.setDataSource(workingMemoryDataSource);
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mybatis/mapper/working_memory_*_mapper.xml"));
            return bean.getObject();
        }

        @Bean PlatformTransactionManager transactionManager(DataSource workingMemoryDataSource) {
            return new DataSourceTransactionManager(workingMemoryDataSource);
        }

        @Bean WorkingMemoryProjector workingMemoryProjector() {
            return new WorkingMemoryProjector();
        }

        @Bean MysqlSessionWorkingMemoryRepository repository(IWorkingMemoryTurnDao turns,
                IWorkingMemoryMessageDao messages, IWorkingMemoryCompactionDao events, WorkingMemoryProjector projector) {
            return new MysqlSessionWorkingMemoryRepository(turns, messages, events, projector);
        }
    }
}
