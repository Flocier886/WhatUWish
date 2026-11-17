package com.flocier.domain.agent.runtime.memory.review;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.*;
import com.flocier.domain.agent.runtime.llm.message.*;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对照 master BackgroundReviewServiceImpl：成功计数、同会话防重入及有界后台执行。
 * 仅调度器写执行终态，fork 只操作私有状态，避免取消后的晚返回覆盖 TIMEOUT。
 */
@Slf4j
@Service
public class BackgroundSkillReviewService {
    private final SkillReviewOptions options;
    private final SkillRuntimeOptions skillOptions;
    private final SkillReviewRunner runner;
    private final SkillReviewFiles files;
    private final ObjectProvider<SkillReviewExecutionRepository> repositories;
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService reviews;
    private final ExecutorService forks;

    @org.springframework.beans.factory.annotation.Autowired
    public BackgroundSkillReviewService(SkillReviewOptions options, SkillRuntimeOptions skillOptions,
            SkillReviewRunner runner, SkillReviewFiles files, ObjectProvider<SkillReviewExecutionRepository> repositories) {
        this(options, skillOptions, runner, files, repositories, executor("skill-review", 64), executor("skill-review-fork", 32));
    }

    /** 可注入执行器仅用于定向控制调度边界，正式构造始终使用有界池。 */
    public BackgroundSkillReviewService(SkillReviewOptions options, SkillRuntimeOptions skillOptions,
            SkillReviewRunner runner, SkillReviewFiles files, ObjectProvider<SkillReviewExecutionRepository> repositories,
            ExecutorService reviews, ExecutorService forks) {
        this.options = options; this.skillOptions = skillOptions; this.runner = runner; this.files = files;
        this.repositories = repositories; this.reviews = reviews; this.forks = forks;
    }

    private static ExecutorService executor(String name, int capacity) {
        AtomicInteger ids = new AtomicInteger();
        return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, name + "-" + ids.incrementAndGet()); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 仅在主流程成功交付后调用；全部调度异常止于此处，不向主任务传播。 */
    public void maybeScheduleAfterSuccess(AgentContext parent) {
        try {
            if (!eligible(parent)) return;
            int count = counters.computeIfAbsent(parent.getSessionId(), key -> new AtomicInteger()).incrementAndGet();
            if (count % options.getNudgeInterval() != 0) return;
            SkillReviewExecution execution = execution(parent, count);
            if (!inFlight.add(parent.getSessionId())) { recordSkip(execution, "in-flight"); return; }
            try {
                SkillReviewRunner.Snapshot snapshot = runner.snapshot(parent);
                reviews.execute(() -> {
                    try { execute(snapshot, execution); }
                    catch (Exception e) { log.warn("Skill 复盘后台异常，reviewId={}", execution.getReviewId(), e); }
                    finally { inFlight.remove(execution.getSessionId()); }
                });
            } catch (RejectedExecutionException e) {
                inFlight.remove(parent.getSessionId()); recordSkip(execution, "queue-full");
            } catch (Exception e) {
                inFlight.remove(parent.getSessionId()); throw e;
            }
        } catch (Exception e) { log.warn("Skill 复盘调度已跳过，不影响主结果", e); }
    }

    /** 无终答、耗尽预算及嵌套 fork 均不增加成功计数。 */
    private boolean eligible(AgentContext parent) {
        if (!options.isEnabled() || !skillOptions.isEnabled() || !skillOptions.isReactEnabled()
                || !skillOptions.isMasterStyleToolsEnabled() || parent == null || AgentRunMode.isReview(parent)
                || parent.getSessionId() == null || parent.getSessionId().isBlank()
                || "max_step_reached".equals(parent.getFinishReason())) return false;
        String system = parent.getAttribute(com.flocier.domain.agent.runtime.agent.react.ReActAgent.REACT_SYSTEM_PROMPT_KEY);
        List<LlmMessage> messages = parent.getReactMemorySnapshot();
        if (system == null || system.isBlank() || messages == null || messages.isEmpty()) return false;
        LlmMessage last = messages.get(messages.size() - 1);
        return last != null && last.getRole() == LlmMessageRole.ASSISTANT && last.getContent() != null
                && !last.getContent().isBlank() && (last.getToolCalls() == null || last.getToolCalls().isEmpty());
    }

    private SkillReviewExecution execution(AgentContext parent, int count) {
        SkillReviewExecution row = new SkillReviewExecution();
        row.setReviewId(UUID.randomUUID().toString()); row.setForkRequestId(UUID.randomUUID().toString());
        row.setSessionId(parent.getSessionId()); row.setTriggerRequestId(parent.getRequestId());
        row.setTriggerRunId(parent.getAttribute("runId")); row.setSuccessTurnCount(count);
        row.setSnapshotMessageCount(parent.getReactMemorySnapshot().size());
        row.setMaxSteps(options.getMaxSteps()); row.setTimeoutSeconds(options.getTimeoutSeconds());
        row.setChangedFilesJson("[]"); row.setRefreshStatus("NOT_NEEDED");
        return row;
    }

    private void recordSkip(SkillReviewExecution execution, String reason) {
        try {
            SkillReviewExecutionRepository repository = repositories.getIfAvailable();
            if (repository == null) return;
            execution.setStatus("SKIPPED"); execution.setSkipReason(reason); repository.insert(execution);
        } catch (Exception e) { log.warn("Skill 复盘跳过记录失败，reviewId={}", execution.getReviewId(), e); }
    }

    /** 两个独立有界池避免调度线程等待同池子任务；审计建立失败就不开始文件操作。 */
    private void execute(SkillReviewRunner.Snapshot snapshot, SkillReviewExecution execution) {
        SkillReviewExecutionRepository repository = repositories.getIfAvailable();
        if (repository == null) { log.warn("Skill 复盘缺少持久化适配器，跳过后台写入"); return; }
        execution.setStatus("RUNNING");
        try { repository.insert(execution); }
        catch (Exception e) { log.warn("Skill 复盘 RUNNING 记录建立失败，跳过后台写入，reviewId={}", execution.getReviewId(), e); return; }
        long started = System.nanoTime();
        SkillReviewState state = new SkillReviewState();
        Future<Boolean> future = null;
        try {
            future = forks.submit(() -> runner.run(snapshot, execution, state));
            boolean success = future.get(execution.getTimeoutSeconds(), TimeUnit.SECONDS);
            execution.setStatus(success ? "SUCCESS" : "FAILED");
            if (!success) execution.setErrorCode("review_incomplete");
        } catch (TimeoutException e) {
            execution.setStatus("TIMEOUT"); execution.setErrorCode("review_timeout");
        } catch (RejectedExecutionException e) {
            execution.setStatus("SKIPPED"); execution.setSkipReason("fork-queue-full");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); execution.setStatus("FAILED"); execution.setErrorCode("review_interrupted");
        } catch (Exception e) {
            execution.setStatus("FAILED"); execution.setErrorCode("review_execution_failed");
            execution.setErrorMessage("后台复盘执行异常；详见服务端日志");
            log.warn("Skill 复盘失败，reviewId={}", execution.getReviewId(), e);
        } finally {
            // 取消与短文件操作使用同一锁：此后不能新增写入，已完成写入全部纳入刷新。
            state.cancel();
            if (future != null && !future.isDone()) future.cancel(true);
            SkillReviewFiles.RefreshResult refresh = files.refresh(state);
            execution.setChangedFileCount(state.changes().size());
            execution.setChangedFilesJson(JSON.toJSONString(state.changes().stream().limit(100).toList()));
            execution.setRefreshStatus(refresh.status()); execution.setValidationSummary(limit(refresh.summary(), 2000));
            if ("SUCCESS".equals(execution.getStatus()) && !Set.of("SUCCESS", "NOT_NEEDED").contains(refresh.status())) {
                execution.setStatus("FAILED"); execution.setErrorCode("skill_registration_failed");
            }
            execution.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            try { repository.finish(execution); }
            catch (Exception e) { log.warn("Skill 复盘终态保存失败，可能遗留 RUNNING，reviewId={}", execution.getReviewId(), e); }
        }
    }

    private String limit(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }

    @PreDestroy
    public void close() { reviews.shutdownNow(); forks.shutdownNow(); }
}
