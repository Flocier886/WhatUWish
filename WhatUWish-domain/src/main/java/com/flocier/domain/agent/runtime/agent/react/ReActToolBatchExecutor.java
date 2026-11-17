package com.flocier.domain.agent.runtime.agent.react;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** ReAct 同轮工具调用的专用有界执行器，不承载心跳或其他运行时任务。 */
@Component
public class ReActToolBatchExecutor implements Executor {

    private static final int MAX_CONCURRENCY = 3;
    private final ExecutorService delegate = new ThreadPoolExecutor(
            MAX_CONCURRENCY,
            MAX_CONCURRENCY,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new NamedThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @PreDestroy
    void shutdown() {
        delegate.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "react-tool-batch-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
