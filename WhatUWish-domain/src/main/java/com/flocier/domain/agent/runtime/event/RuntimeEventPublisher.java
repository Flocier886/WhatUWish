package com.flocier.domain.agent.runtime.event;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 传输无关的运行展示事件发布端口。 */
@FunctionalInterface
public interface RuntimeEventPublisher {
    void publish(RuntimeEventDraft draft);

    /** 只有持久化事务确认后才允许把运行事实投影给订阅端。 */
    default void publishAfterCommit(RuntimeEventDraft draft) {
        if (draft == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(draft);
                }
            });
            return;
        }
        publish(draft);
    }

    static RuntimeEventPublisher noop() {
        return draft -> { };
    }
}
