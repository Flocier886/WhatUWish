package com.flocier.domain.agent.runtime.event;

import lombok.Getter;

@Getter
public final class RunWaitingUserInputPayload implements RuntimeEventPayload {
    private final String status = "WAITING_USER_INPUT";
    private final String message = "等待用户完成桌面操作";
}
