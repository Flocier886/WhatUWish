package com.flocier.test.domain.ledger;

import com.flocier.application.agent.execute.AgentExecuteCommand;
import com.flocier.application.agent.execute.IExecuteStrategy;
import com.flocier.application.agent.stream.AgentSessionStream;
import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.vo.AiAgentVO;
import com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.service.LedgerQueryService;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class FormalExecuteStrategyLiveTest {

    @Autowired
    @Qualifier("reactAgentExecuteStrategy")
    private IExecuteStrategy reactExecuteStrategy;

    @Autowired
    @Qualifier("workflowAgentExecuteStrategy")
    private IExecuteStrategy workflowExecuteStrategy;

    @Autowired
    private LedgerQueryService ledgerQueryService;
    @Autowired
    private IAgentRepository agentRepository;

    @Test
    public void shouldRunReactFormalEntryAndRecordUnifiedLedger() throws Exception {
        String sessionId = "react-formal-live-" + UUID.randomUUID();
        CapturingStream stream = new CapturingStream();

        reactExecuteStrategy.execute(AgentExecuteCommand.builder()
                .aiAgentId("react-live")
                .sessionId(sessionId)
                .message("Use a tool if needed, then provide one short final answer in Chinese about good weather today.")
                .maxStep(3)
                .build(), stream);

        RunLedgerEntity run = latestRun(sessionId);
        assertFormalRun(sessionId, run, "react");

        LedgerRunView runView = ledgerQueryService.queryRunView(run.getRunId()).orElse(null);
        Assert.assertNotNull(runView);
        Assert.assertNotNull(runView.getRun());
        Assert.assertFalse(runView.getLlmInvocations().isEmpty());
        Assert.assertFalse(runView.getArtifacts().isEmpty());
        Assert.assertTrue(stream.hasEventType("message"));
    }

    @Test
    public void shouldRunWorkflowFixedFormalEntryAndRecordUnifiedLedger() throws Exception {
        String sessionId = "workflow-formal-live-" + UUID.randomUUID();
        String agentId = resolveAgentIdForFixedWorkflow();
        CapturingStream stream = new CapturingStream();

        workflowExecuteStrategy.execute(AgentExecuteCommand.builder()
                .aiAgentId(agentId)
                .sessionId(sessionId)
                .message("Summarize the difference between interface and abstract class in one concise Chinese paragraph.")
                .maxStep(3)
                .legacyStrategy("fixedAgentExecuteStrategy")
                .build(), stream);

        RunLedgerEntity run = latestRun(sessionId);
        assertFormalRun(sessionId, run, "workflow");

        LedgerRunView runView = ledgerQueryService.queryRunView(run.getRunId()).orElse(null);
        Assert.assertNotNull(runView);
        Assert.assertNotNull(runView.getRun());
        Assert.assertFalse(runView.getLlmInvocations().isEmpty());
        Assert.assertFalse(runView.getArtifacts().isEmpty());
        Assert.assertTrue(stream.hasEventType("message"));
    }

    @Test
    public void shouldCreateDifferentRunIdsForTwoRequestsInSameSession() throws Exception {
        String sessionId = "react-formal-repeat-" + UUID.randomUUID();

        reactExecuteStrategy.execute(AgentExecuteCommand.builder()
                .aiAgentId("react-live")
                .sessionId(sessionId)
                .message("""
                        必须调用 artifact_save，创建一个名为 memory-live-check-01.txt 的文本产物，
                        正文必须是：MEMORY_LIVE_MARKER_20260723_21_20。
                        完成后简短说明文件已保存。
                        """)
                .maxStep(2)
                .build(), new CapturingStream());

        reactExecuteStrategy.execute(AgentExecuteCommand.builder()
                .aiAgentId("react-live")
                .sessionId(sessionId)
                .message("""
                        基于同一会话上一轮的执行历史，找到上一轮生成的 artifact。
                        读取并返回其中的唯一标记内容。
                        不要重新创建文件；若需要文件正文，调用 artifact_read。
                        """)
                .maxStep(2)
                .build(), new CapturingStream());

        List<RunLedgerEntity> runs = ledgerQueryService.queryRuns(LedgerRunQueryEntity.builder()
                .sessionId(sessionId)
                .limit(10)
                .build());

        Assert.assertTrue(runs.size() >= 2);
        HashSet<String> runIds = new HashSet<>();
        HashSet<String> requestIds = new HashSet<>();
        for (RunLedgerEntity run : runs) {
            runIds.add(run.getRunId());
            requestIds.add(run.getRequestId());
            Assert.assertEquals(sessionId, run.getSessionId());
        }
        Assert.assertTrue(runIds.size() >= 2);
        Assert.assertTrue(requestIds.size() >= 2);
        Assert.assertFalse(runIds.contains(sessionId));
    }

    private RunLedgerEntity latestRun(String sessionId) {
        List<RunLedgerEntity> runs = ledgerQueryService.queryRuns(LedgerRunQueryEntity.builder()
                .sessionId(sessionId)
                .limit(10)
                .build());
        Assert.assertFalse(runs.isEmpty());
        return runs.get(runs.size() - 1);
    }

    private void assertFormalRun(String sessionId, RunLedgerEntity run, String triggerSource) {
        Assert.assertNotNull(run);
        Assert.assertEquals(sessionId, run.getSessionId());
        Assert.assertNotNull(run.getRunId());
        Assert.assertNotNull(run.getRequestId());
        Assert.assertNotNull(run.getTraceId());
        Assert.assertFalse(run.getRunId().isBlank());
        Assert.assertFalse(run.getRequestId().isBlank());
        Assert.assertFalse(run.getTraceId().isBlank());
        Assert.assertFalse(sessionId.equals(run.getRunId()));
        Assert.assertFalse(sessionId.equals(run.getRequestId()));
        Assert.assertEquals(triggerSource, run.getTriggerSource());
        Assert.assertTrue("COMPLETED".equalsIgnoreCase(run.getStatus()) || "RUNNING".equalsIgnoreCase(run.getStatus()));
    }

    private String resolveAgentIdForFixedWorkflow() {
        for (AiAgentVO agent : agentRepository.queryAvailableAgents()) {
            if (agent == null || agent.getAgentId() == null) {
                continue;
            }
            if (!agentRepository.queryAiClientsByAgentId(agent.getAgentId()).isEmpty()) {
                return agent.getAgentId();
            }
        }
        Assert.fail("No available agent with client flow configuration was found for workflow fixed live test.");
        return null;
    }

    private static class CapturingStream implements AgentSessionStream {
        private final List<String> eventTypes = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();

        @Override
        public void send(String eventType, Object payload) {
            eventTypes.add(eventType);
            payloads.add(payload);
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(String message, Throwable throwable) {
            throw new RuntimeException(message, throwable);
        }

        private boolean hasEventType(String eventType) {
            return eventTypes.stream().anyMatch(eventType::equals);
        }
    }
}
