package com.flocier.test.domain.memory;

import com.flocier.domain.agent.runtime.agent.*;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.llm.message.*;
import com.flocier.domain.agent.runtime.memory.review.*;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import org.junit.*;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 只控制后台边界，模型和仓储替身不进入正式运行逻辑。 */
public class BackgroundSkillReviewServiceTest {
    private final SkillReviewOptions options = new SkillReviewOptions();
    private final SkillReviewRunner runner = mock(SkillReviewRunner.class);
    private final SkillReviewFiles files = mock(SkillReviewFiles.class);
    private final SkillReviewExecutionRepository repository = mock(SkillReviewExecutionRepository.class);
    private final ManualExecutor reviews = new ManualExecutor();
    private final ExecutorService forks = Executors.newSingleThreadExecutor();
    private BackgroundSkillReviewService service;
    private final List<SkillReviewExecution> inserted = new ArrayList<>();
    private final List<SkillReviewExecution> finished = new ArrayList<>();

    @Before @SuppressWarnings("unchecked") public void setup() {
        SkillRuntimeOptions skills = SkillRuntimeOptions.builder().enabled(true).reactEnabled(true).masterStyleToolsEnabled(true).build();
        ObjectProvider<SkillReviewExecutionRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        doReturn(new SkillReviewRunner.Snapshot("session", "request", "run", "system", List.of(), "", Map.of())).when(runner).snapshot(any());
        when(runner.run(any(), any(), any())).thenReturn(true);
        when(files.refresh(any())).thenReturn(new SkillReviewFiles.RefreshResult("NOT_NEEDED", "无变化"));
        doAnswer(call -> { inserted.add(copy(call.getArgument(0))); return null; }).when(repository).insert(any());
        doAnswer(call -> { finished.add(copy(call.getArgument(0))); return null; }).when(repository).finish(any());
        service = new BackgroundSkillReviewService(options, skills, runner, files, provider, reviews, forks);
    }
    @After public void close() { service.close(); }

    @Test public void countsOnlyEligibleSuccessAndRecordsNoChange() {
        options.setEnabled(false);
        service.maybeScheduleAfterSuccess(parent());
        options.setEnabled(true);
        AgentContext invalid = parent(); invalid.setFinishReason("max_step_reached");
        service.maybeScheduleAfterSuccess(invalid);
        invalid = parent(); invalid.setRunMode(AgentRunMode.SKILL_REVIEW); service.maybeScheduleAfterSuccess(invalid);
        invalid = parent(); invalid.setReactMemorySnapshot(List.of()); service.maybeScheduleAfterSuccess(invalid);
        for (int i = 0; i < 4; i++) service.maybeScheduleAfterSuccess(parent());
        assertTrue(reviews.pending.isEmpty()); verifyNoInteractions(repository);
        service.maybeScheduleAfterSuccess(parent());
        assertEquals(1, reviews.pending.size());
        reviews.runNext();
        assertEquals("RUNNING", inserted.get(0).getStatus());
        assertEquals(5, finished.get(0).getSuccessTurnCount());
        assertEquals("SUCCESS", finished.get(0).getStatus());
        assertEquals(0, finished.get(0).getChangedFileCount());
        assertEquals("NOT_NEEDED", finished.get(0).getRefreshStatus());
    }

    @Test public void deduplicatesInFlightAndRecoversAfterQueueRejection() {
        options.setNudgeInterval(1);
        service.maybeScheduleAfterSuccess(parent());
        service.maybeScheduleAfterSuccess(parent());
        assertEquals(1, reviews.pending.size());
        assertEquals("in-flight", inserted.get(0).getSkipReason());
        reviews.runNext();
        reviews.reject = true; service.maybeScheduleAfterSuccess(parent());
        assertEquals("queue-full", inserted.get(inserted.size() - 1).getSkipReason());
        reviews.reject = false; service.maybeScheduleAfterSuccess(parent());
        assertEquals(1, reviews.pending.size());
        reviews.runNext();
        assertEquals(2, finished.size());
    }

    @Test public void auditInsertFailurePreventsModelAndFilesWithoutEscaping() {
        options.setNudgeInterval(1);
        doThrow(new IllegalStateException("数据库不可用")).when(repository).insert(any());
        service.maybeScheduleAfterSuccess(parent()); reviews.runNext();
        verify(runner, never()).run(any(), any(), any()); verifyNoInteractions(files);
        assertTrue(finished.isEmpty());
    }

    @Test public void snapshotAndTerminalAuditFailureCannotEscapeToMainCaller() {
        options.setNudgeInterval(1);
        when(runner.snapshot(any())).thenThrow(new IllegalStateException("快照失败"));
        service.maybeScheduleAfterSuccess(parent()); assertTrue(reviews.pending.isEmpty());
        doReturn(new SkillReviewRunner.Snapshot("session", "request", "run", "system", List.of(), "", Map.of())).when(runner).snapshot(any());
        doThrow(new IllegalStateException("终态不可写")).when(repository).finish(any());
        service.maybeScheduleAfterSuccess(parent()); reviews.runNext();
        verify(runner).run(any(), any(), any());
    }

    @Test public void incompleteAndInvalidRegistrationRemainFailed() {
        options.setNudgeInterval(1);
        when(runner.run(any(), any(), any())).thenReturn(false, true);
        service.maybeScheduleAfterSuccess(parent()); reviews.runNext();
        assertEquals("review_incomplete", finished.get(0).getErrorCode());
        when(files.refresh(any())).thenReturn(new SkillReviewFiles.RefreshResult("INVALID", "非法包"));
        service.maybeScheduleAfterSuccess(parent()); reviews.runNext();
        assertEquals("FAILED", finished.get(1).getStatus());
        assertEquals("skill_registration_failed", finished.get(1).getErrorCode());
    }

    @Test public void saturatedForkPoolIsRecordedAndDoesNotRunModel() {
        options.setNudgeInterval(1); forks.shutdownNow();
        service.maybeScheduleAfterSuccess(parent()); reviews.runNext();
        assertEquals("SKIPPED", finished.get(0).getStatus());
        assertEquals("fork-queue-full", finished.get(0).getSkipReason());
        verify(runner, never()).run(any(), any(), any());
    }

    @Test public void timeoutKeepsPartialChangesAndLateReturnCannotOverwriteTerminal() throws Exception {
        options.setNudgeInterval(1); options.setTimeoutSeconds(1);
        CountDownLatch release = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicReference<SkillReviewState> stateRef = new AtomicReference<>();
        when(runner.run(any(), any(), any())).thenAnswer(call -> {
            SkillReviewState state = call.getArgument(2); stateRef.set(state);
            synchronized (SkillReviewState.FILE_LOCK) { state.changed(Path.of("test-only.md"), "fixture", "SKILL.md", true); }
            // 模拟暂不响应中断的上游；只能使用私有状态，不能写审计终态。
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
            try { state.checkActive(); fail("超时后的晚返回必须已取消"); }
            catch (CancellationException expected) { }
            returned.countDown(); return true;
        });
        when(files.refresh(any())).thenReturn(new SkillReviewFiles.RefreshResult("SUCCESS", "部分包已刷新"));
        try {
            service.maybeScheduleAfterSuccess(parent()); reviews.runNext();
            assertEquals("TIMEOUT", finished.get(0).getStatus());
            assertEquals(1, finished.get(0).getChangedFileCount());
            assertTrue(finished.get(0).getChangedFilesJson().contains("SKILL.md"));
            assertThrows(CancellationException.class, () -> stateRef.get().checkActive());
            verify(files).refresh(stateRef.get());
        } finally { release.countDown(); }
        assertTrue(returned.await(5, TimeUnit.SECONDS));
        verify(repository, times(1)).finish(any());
        assertEquals("TIMEOUT", finished.get(0).getStatus());
    }

    private AgentContext parent() {
        AgentContext parent = AgentContext.builder().sessionId("session").requestId("request").build();
        parent.setAttribute("runId", "run"); parent.setAttribute(ReActAgent.REACT_SYSTEM_PROMPT_KEY, "system");
        parent.setReactMemorySnapshot(List.of(LlmMessage.builder().role(LlmMessageRole.ASSISTANT).content("已成功完成").build()));
        return parent;
    }
    private SkillReviewExecution copy(SkillReviewExecution row) {
        return com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(row), SkillReviewExecution.class);
    }
    private static class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> pending = new ArrayDeque<>(); boolean reject;
        public void execute(Runnable task) { if (reject) throw new RejectedExecutionException(); pending.add(task); }
        void runNext() { pending.remove().run(); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return List.copyOf(pending); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
