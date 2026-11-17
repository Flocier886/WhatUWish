package com.flocier.domain.agent.runtime.event;

import lombok.Getter;

/** Run 运行状态事件的受控展示数据。 */
@Getter
public final class RunStatusPayload implements RuntimeEventPayload {

    private final String status;
    private final String message;

    public RunStatusPayload(String status, String message) {
        this.status = status;
        this.message = message;
    }
}
