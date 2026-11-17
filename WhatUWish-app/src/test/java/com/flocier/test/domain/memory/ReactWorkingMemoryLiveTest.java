package com.flocier.test.domain.memory;

import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.domain.agent.runtime.memory.SessionContextCompactionService;
import com.flocier.domain.agent.runtime.memory.WorkingMemoryCompactor;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import com.flocier.infrastructure.dao.IWorkingMemoryTurnDao;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class ReactWorkingMemoryLiveTest {
    @Autowired private ReactAgentWorkflowExecuteStrategy strategy;
    @Autowired private SessionWorkingMemoryRepository repository;
    @Autowired private SessionContextCompactionService compactionService;
    @Autowired private WorkingMemoryCompactor compactor;
    @Autowired private IWorkingMemoryTurnDao turnDao;

    @Test
    public void twoRealRunsAppendNativeMessagesWithoutDuplicatingHistory() throws Exception {
        String session = "wm-live-" + UUID.randomUUID();
        run(session, "first", "请直接回答：确认。不需要使用工具。");
        List<LlmMessage> first = repository.loadReadyMessages(session, "inspect-first");
        Assert.assertTrue(first.size() >= 2);
        Assert.assertEquals(LlmMessageRole.USER, first.get(0).getRole());
        Assert.assertEquals(LlmMessageRole.ASSISTANT, first.get(first.size() - 1).getRole());

        run(session, "second", "请根据上一轮对话直接回答：你上一轮收到的任务是什么？不需要使用工具。");
        List<LlmMessage> second = repository.loadReadyMessages(session, "inspect-second");
        Assert.assertTrue(second.size() > first.size());
        for (int i = 0; i < first.size(); i++) {
            Assert.assertEquals(first.get(i).getRole(), second.get(i).getRole());
            Assert.assertEquals(first.get(i).getContent(), second.get(i).getContent());
        }
        Assert.assertEquals(1, second.stream().filter(message -> message.getRole() == LlmMessageRole.USER
                && message.getContent() != null && message.getContent().contains("请直接回答：确认")).count());
        Assert.assertTrue(second.get(second.size() - 1).getContent().contains("确认"));
    }

    @Test
    public void realSummaryCompactionReplacesReadyProjectionAndPreservesLatestTask() {
        String session = "wm-compact-live-" + UUID.randomUUID();
        String oldRequest = session + "-old";
        List<LlmMessage> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            history.add(LlmMessage.builder().role(LlmMessageRole.USER)
                    .content("Earlier question " + i + " " + "context ".repeat(150)).build());
            history.add(LlmMessage.builder().role(LlmMessageRole.ASSISTANT)
                    .content("Earlier answer " + i + " " + "verified detail ".repeat(100)).build());
        }
        history.add(LlmMessage.builder().role(LlmMessageRole.USER).content("latest task: continue accurately").build());
        Assert.assertTrue(repository.persistTurn(session, oldRequest, UUID.randomUUID().toString(), history));

        Object originalWindow = ReflectionTestUtils.getField(compactionService, "contextWindow");
        Object originalKeepTokens = ReflectionTestUtils.getField(compactionService, "keepMinTokens");
        Object originalKeepMessages = ReflectionTestUtils.getField(compactionService, "keepMinTextMessages");
        try {
            ReflectionTestUtils.setField(compactionService, "contextWindow", 1000);
            ReflectionTestUtils.setField(compactionService, "keepMinTokens", 100);
            ReflectionTestUtils.setField(compactionService, "keepMinTextMessages", 2);
            List<LlmMessage> compacted = compactionService.applyIfNeeded(session, session + "-current",
                    "stable system", "tools", history);
            Assert.assertTrue(compacted.size() < history.size());
            Assert.assertTrue(compactor.isSummary(compacted.get(0)));
            Assert.assertEquals("latest task: continue accurately", compacted.get(compacted.size() - 1).getContent());
            Assert.assertEquals(2, turnDao.selectByRequestId(oldRequest).getStatus().intValue());
            List<LlmMessage> reloaded = repository.loadReadyMessages(session, "next-request");
            Assert.assertEquals(compacted.size(), reloaded.size());
            for (int i = 0; i < compacted.size(); i++) {
                Assert.assertEquals(compacted.get(i).getRole(), reloaded.get(i).getRole());
                Assert.assertEquals(compacted.get(i).getContent(), reloaded.get(i).getContent());
            }
        } finally {
            ReflectionTestUtils.setField(compactionService, "contextWindow", originalWindow);
            ReflectionTestUtils.setField(compactionService, "keepMinTokens", originalKeepTokens);
            ReflectionTestUtils.setField(compactionService, "keepMinTextMessages", originalKeepMessages);
        }
    }

    @Test
    public void realReactRunContinuesInNewSegmentAfterCompaction() throws Exception {
        String session = "wm-react-compact-" + UUID.randomUUID();
        String oldRequest = session + "-old";
        String request = session + "-current";
        List<LlmMessage> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            history.add(LlmMessage.builder().role(LlmMessageRole.USER)
                    .content("Earlier question " + i + " " + "context ".repeat(150)).build());
            history.add(LlmMessage.builder().role(LlmMessageRole.ASSISTANT)
                    .content("Earlier answer " + i + " " + "verified detail ".repeat(100)).build());
        }
        Assert.assertTrue(repository.persistTurn(session, oldRequest, UUID.randomUUID().toString(), history));

        Object originalWindow = ReflectionTestUtils.getField(compactionService, "contextWindow");
        Object originalKeepTokens = ReflectionTestUtils.getField(compactionService, "keepMinTokens");
        Object originalKeepMessages = ReflectionTestUtils.getField(compactionService, "keepMinTextMessages");
        try {
            ReflectionTestUtils.setField(compactionService, "contextWindow", 1000);
            ReflectionTestUtils.setField(compactionService, "keepMinTokens", 100);
            ReflectionTestUtils.setField(compactionService, "keepMinTextMessages", 2);
            String task = "请直接用一句中文回答：当前任务是压缩后的续写验证。不需要调用工具。";
            AgentContext context = AgentContext.builder().sessionId(session).requestId(request)
                    .query(task).task(task).maxStep(2).build();
            ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                    .aiAgentId("react-live").sessionId(session).message(task).maxStep(2).build();
            strategy.execute(command, context);

            String activeSegment = context.getAttribute("workingMemory.segmentId");
            Assert.assertNotNull(activeSegment);
            Assert.assertNotEquals(request, activeSegment);
            Assert.assertEquals(2, turnDao.selectByRequestId(oldRequest).getStatus().intValue());
            Assert.assertEquals(2, turnDao.selectByRequestId(request).getStatus().intValue());
            Assert.assertEquals(1, turnDao.selectByRequestId(activeSegment).getStatus().intValue());
            List<LlmMessage> reloaded = repository.loadReadyMessages(session, "next-request");
            Assert.assertTrue(compactor.isSummary(reloaded.get(0)));
            Assert.assertEquals(context.getReactMemorySnapshot().size(), reloaded.size());
            Assert.assertEquals(LlmMessageRole.ASSISTANT, reloaded.get(reloaded.size() - 1).getRole());
        } finally {
            ReflectionTestUtils.setField(compactionService, "contextWindow", originalWindow);
            ReflectionTestUtils.setField(compactionService, "keepMinTokens", originalKeepTokens);
            ReflectionTestUtils.setField(compactionService, "keepMinTextMessages", originalKeepMessages);
        }
    }

    private void run(String session, String turn, String task) throws Exception {
        AgentContext context = AgentContext.builder()
                .sessionId(session).requestId(session + "-" + turn)
                .query(task).task(task).maxStep(3).build();
        ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                .aiAgentId("react-live").sessionId(session).message(task).maxStep(3).build();
        strategy.execute(command, context);
        Assert.assertNotEquals("max_step_reached", context.getFinishReason());
    }
}
