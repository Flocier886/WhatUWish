package com.flocier.domain.agent.runtime.event;

import lombok.Getter;

/** Run 开始事件的受控展示数据。 */
@Getter
public final class RunStartedPayload implements RuntimeEventPayload {

    private final String status;
    private final String task;

    public RunStartedPayload(String status, String task) {
        this.status = status;
        this.task = task;
    }
}
