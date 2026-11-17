package com.flocier.domain.agent.runtime.event;

import lombok.Getter;

/** Run 失败事件的受控展示数据。 */
@Getter
public final class RunFailedPayload implements RuntimeEventPayload {

    private final String status;
    private final String errorCode;
    private final String errorMessage;

    public RunFailedPayload(String status, String errorCode, String errorMessage) {
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}
