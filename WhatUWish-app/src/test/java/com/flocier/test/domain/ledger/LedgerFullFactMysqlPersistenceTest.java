package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.ILlmInvocationLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IRunLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.ISessionLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IToolInvocationLedgerRepository;
import com.flocier.domain.ledger.service.LedgerQueryService;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import com.flocier.infrastructure.adapter.repository.MysqlArtifactLedgerRepository;
import com.flocier.infrastructure.adapter.repository.MysqlLlmInvocationLedgerRepository;
import com.flocier.infrastructure.adapter.repository.MysqlRunLedgerRepository;
import com.flocier.infrastructure.adapter.repository.MysqlSessionLedgerRepository;
import com.flocier.infrastructure.adapter.repository.MysqlToolInvocationLedgerRepository;
import jakarta.annotation.Resource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = LedgerFullFactMysqlPersistenceTest.LedgerMysqlTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
public class LedgerFullFactMysqlPersistenceTest {

    @Resource
    private LedgerRecordingService ledgerRecordingService;

    @Resource
    private LedgerQueryService ledgerQueryService;

    @Test
    public void shouldPersistAndReadBackFullPayloadThroughMysqlMapper() {
        String suffix = UUID.randomUUID().toString();
        String runId = "mysql-full-fact-run-" + suffix;
        String requestId = "mysql-full-fact-request-" + suffix;
        String sessionId = "mysql-full-fact-session-" + suffix;
        String traceId = "mysql-full-fact-trace-" + suffix;
        String llmTail = "MYSQL_LLM_TAIL_" + suffix;
        String toolTail = "MYSQL_TOOL_TAIL_" + suffix;
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .traceId(traceId)
                .currentStep(1)
                .build();
        context.setAttribute("runId", runId);

        ledgerRecordingService.recordRunStarted(context, "mysql-full-fact-test", "test");
        ledgerRecordingService.recordLlmCall(LlmRequest.builder()
                        .model("mysql-full-fact-model")
                        .messages(List.of(LlmMessage.builder()
                                .role(LlmMessageRole.USER)
                                .content("l".repeat(350) + llmTail)
                                .build()))
                        .attributes(Map.of(
                                "runId", runId,
                                "requestId", requestId,
                                "sessionId", sessionId,
                                "traceId", traceId,
                                "scene", "mysql_test",
                                "step", 1
                        ))
                        .build(),
                LlmResponse.builder()
                        .provider("mysql-test-provider")
                        .model("mysql-full-fact-model")
                        .text("r".repeat(350) + llmTail)
                        .finishReason("stop")
                        .build());
        ledgerRecordingService.recordToolCall(context,
                ToolInvokeRequest.builder()
                        .toolName("mysql_full_fact_tool")
                        .toolCallId("mysql-call-" + suffix)
                        .arguments(Map.of("content", "a".repeat(350) + toolTail))
                        .requestId(requestId)
                        .sessionId(sessionId)
                        .step(1)
                        .build(),
                ToolExecutionResult.builder()
                        .toolName("mysql_full_fact_tool")
                        .toolCallId("mysql-call-" + suffix)
                        .sourceType("test")
                        .success(true)
                        .status("success")
                        .outputText("o".repeat(350) + toolTail)
                        .llmObservation("v".repeat(350) + toolTail)
                        .structuredOutput(Map.of("tail", toolTail))
                        .startedAt(Instant.now())
                        .finishedAt(Instant.now())
                        .durationMs(1L)
                        .build());

        LedgerRunView runView = ledgerQueryService.queryRunView(runId).orElse(null);
        Assert.assertNotNull(runView);
        Assert.assertEquals(1, runView.getLlmInvocations().size());
        Assert.assertEquals(1, runView.getToolInvocations().size());
        Assert.assertTrue(runView.getLlmInvocations().get(0).getRequestPayloadJson().contains(llmTail));
        Assert.assertTrue(runView.getLlmInvocations().get(0).getResponsePayloadJson().contains(llmTail));
        Assert.assertTrue(runView.getToolInvocations().get(0).getRequestPayloadJson().contains(toolTail));
        Assert.assertTrue(runView.getToolInvocations().get(0).getResponsePayloadJson().contains(toolTail));
    }

    @Configuration
    @MapperScan(basePackages = "com.flocier.infrastructure.dao", sqlSessionFactoryRef = "ledgerSqlSessionFactory")
    static class LedgerMysqlTestConfiguration {

        @Bean
        DataSource ledgerDataSource(org.springframework.core.env.Environment environment) {
            return DataSourceBuilder.create()
                    .driverClassName(environment.getRequiredProperty("spring.datasource.mysql.driver-class-name"))
                    .url(environment.getRequiredProperty("spring.datasource.mysql.url"))
                    .username(environment.getRequiredProperty("spring.datasource.mysql.username"))
                    .password(environment.getRequiredProperty("spring.datasource.mysql.password"))
                    .build();
        }

        @Bean
        SqlSessionFactory ledgerSqlSessionFactory(DataSource ledgerDataSource) throws Exception {
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(ledgerDataSource);
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mybatis/mapper/*_mapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        MysqlSessionLedgerRepository mysqlSessionLedgerRepository() {
            return new MysqlSessionLedgerRepository();
        }

        @Bean
        MysqlRunLedgerRepository mysqlRunLedgerRepository() {
            return new MysqlRunLedgerRepository();
        }

        @Bean
        MysqlLlmInvocationLedgerRepository mysqlLlmInvocationLedgerRepository() {
            return new MysqlLlmInvocationLedgerRepository();
        }

        @Bean
        MysqlToolInvocationLedgerRepository mysqlToolInvocationLedgerRepository() {
            return new MysqlToolInvocationLedgerRepository();
        }

        @Bean
        MysqlArtifactLedgerRepository mysqlArtifactLedgerRepository() {
            return new MysqlArtifactLedgerRepository();
        }

        @Bean
        LedgerRecordingService ledgerRecordingService(ISessionLedgerRepository sessionLedgerRepository,
                                                      IRunLedgerRepository runLedgerRepository,
                                                      ILlmInvocationLedgerRepository llmInvocationLedgerRepository,
                                                      IToolInvocationLedgerRepository toolInvocationLedgerRepository,
                                                      IArtifactLedgerRepository artifactLedgerRepository) {
            return new LedgerRecordingService(
                    sessionLedgerRepository,
                    runLedgerRepository,
                    llmInvocationLedgerRepository,
                    toolInvocationLedgerRepository,
                    artifactLedgerRepository
            );
        }

        @Bean
        LedgerQueryService ledgerQueryService(ISessionLedgerRepository sessionLedgerRepository,
                                              IRunLedgerRepository runLedgerRepository,
                                              ILlmInvocationLedgerRepository llmInvocationLedgerRepository,
                                              IToolInvocationLedgerRepository toolInvocationLedgerRepository,
                                              IArtifactLedgerRepository artifactLedgerRepository) {
            return new LedgerQueryService(
                    sessionLedgerRepository,
                    runLedgerRepository,
                    llmInvocationLedgerRepository,
                    toolInvocationLedgerRepository,
                    artifactLedgerRepository
            );
        }
    }
}
