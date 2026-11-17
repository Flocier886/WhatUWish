package com.flocier.domain.agent.runtime.event;

import java.util.List;
import java.util.Map;
import lombok.Getter;

/** 工具结果事件的受控展示数据。 */
@Getter
public final class ToolResultPayload implements RuntimeEventPayload {

    private final String toolInvocationId;
    private final String toolCallId;
    private final String parentLlmInvocationId;
    private final Integer stepNo;
    private final Integer dispatchOrder;
    private final String toolName;
    private final String status;
    private final String association;
    private final String argumentsSummary;
    private final String observation;
    private final String errorCode;
    private final String errorMessage;
    private final List<String> artifactIds;
    private final Map<String, Object> presentation;

    public ToolResultPayload(String toolInvocationId, String toolCallId, String parentLlmInvocationId, Integer stepNo,
                             String toolName, String status, String association, String argumentsSummary,
                             String observation, String errorCode, String errorMessage, List<String> artifactIds) {
        this(toolInvocationId, toolCallId, parentLlmInvocationId, stepNo, null, toolName, status, association,
                argumentsSummary, observation, errorCode, errorMessage, artifactIds);
    }

    public ToolResultPayload(String toolInvocationId, String toolCallId, String parentLlmInvocationId, Integer stepNo,
                             Integer dispatchOrder, String toolName, String status, String association, String argumentsSummary,
                             String observation, String errorCode, String errorMessage, List<String> artifactIds) {
        this(toolInvocationId, toolCallId, parentLlmInvocationId, stepNo, dispatchOrder, toolName, status,
                association, argumentsSummary, observation, errorCode, errorMessage, artifactIds, null);
    }

    public ToolResultPayload(String toolInvocationId, String toolCallId, String parentLlmInvocationId, Integer stepNo,
                             Integer dispatchOrder, String toolName, String status, String association, String argumentsSummary,
                             String observation, String errorCode, String errorMessage, List<String> artifactIds,
                             Map<String, Object> presentation) {
        this.toolInvocationId = toolInvocationId;
        this.toolCallId = toolCallId;
        this.parentLlmInvocationId = parentLlmInvocationId;
        this.stepNo = stepNo;
        this.dispatchOrder = dispatchOrder;
        this.toolName = toolName;
        this.status = status;
        this.association = association;
        this.argumentsSummary = argumentsSummary;
        this.observation = observation;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.artifactIds = artifactIds;
        this.presentation = presentation;
    }
}
