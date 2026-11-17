package com.flocier.domain.agent.runtime.event;

import lombok.Getter;

/** 工具开始事件的受控展示数据。 */
@Getter
public final class ToolStartedPayload implements RuntimeEventPayload {

    private final String toolInvocationId;
    private final String toolCallId;
    private final String parentLlmInvocationId;
    private final Integer stepNo;
    private final Integer dispatchOrder;
    private final String toolName;
    private final String status;
    private final String association;
    private final String argumentsSummary;

    public ToolStartedPayload(String toolInvocationId, String toolCallId, String parentLlmInvocationId,
                              Integer stepNo, String toolName, String status, String association,
                              String argumentsSummary) {
        this(toolInvocationId, toolCallId, parentLlmInvocationId, stepNo, null, toolName, status, association, argumentsSummary);
    }

    public ToolStartedPayload(String toolInvocationId, String toolCallId, String parentLlmInvocationId,
                              Integer stepNo, Integer dispatchOrder, String toolName, String status, String association,
                              String argumentsSummary) {
        this.toolInvocationId = toolInvocationId;
        this.toolCallId = toolCallId;
        this.parentLlmInvocationId = parentLlmInvocationId;
        this.stepNo = stepNo;
        this.dispatchOrder = dispatchOrder;
        this.toolName = toolName;
        this.status = status;
        this.association = association;
        this.argumentsSummary = argumentsSummary;
    }
}
