package com.flocier.domain.ledger.model.replay;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;

/** 通用工具调用的回放展示条目，不携带完整请求或响应原文。 */
@Getter
public final class ToolReplayEntry implements ReplayTimelineEntry {

    private final ReplayEntryMetadata metadata;
    private final String llmInvocationId;
    private final String toolInvocationId;
    private final String toolCallId;
    private final Integer stepNo;
    private final Integer dispatchOrder;
    private final String toolName;
    private final String status;
    private final String association;
    private final String argumentsSummary;
    private final String observation;
    private final String errorCode;
    private final String errorMessage;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Map<String, Object> presentation;

    public ToolReplayEntry(ReplayEntryMetadata metadata, String llmInvocationId, String toolInvocationId,
                           String toolCallId, Integer stepNo, String toolName, String status, String association,
                           String argumentsSummary, String observation, String errorCode, String errorMessage) {
        this(metadata, llmInvocationId, toolInvocationId, toolCallId, stepNo, null, toolName, status, association,
                argumentsSummary, observation, errorCode, errorMessage, null, null);
    }

    public ToolReplayEntry(ReplayEntryMetadata metadata, String llmInvocationId, String toolInvocationId,
                           String toolCallId, Integer stepNo, String toolName, String status, String association,
                           String argumentsSummary, String observation, String errorCode, String errorMessage,
                           Instant startedAt, Instant finishedAt) {
        this(metadata, llmInvocationId, toolInvocationId, toolCallId, stepNo, null, toolName, status, association,
                argumentsSummary, observation, errorCode, errorMessage, startedAt, finishedAt);
    }

    public ToolReplayEntry(ReplayEntryMetadata metadata, String llmInvocationId, String toolInvocationId,
                           String toolCallId, Integer stepNo, Integer dispatchOrder, String toolName, String status,
                           String association, String argumentsSummary, String observation, String errorCode,
                           String errorMessage, Instant startedAt, Instant finishedAt) {
        this(metadata, llmInvocationId, toolInvocationId, toolCallId, stepNo, dispatchOrder, toolName,
                status, association, argumentsSummary, observation, errorCode, errorMessage, startedAt, finishedAt, null);
    }

    public ToolReplayEntry(ReplayEntryMetadata metadata, String llmInvocationId, String toolInvocationId,
                           String toolCallId, Integer stepNo, Integer dispatchOrder, String toolName, String status,
                           String association, String argumentsSummary, String observation, String errorCode,
                           String errorMessage, Instant startedAt, Instant finishedAt, Map<String, Object> presentation) {
        this.metadata = metadata;
        this.llmInvocationId = llmInvocationId;
        this.toolInvocationId = toolInvocationId;
        this.toolCallId = toolCallId;
        this.stepNo = stepNo;
        this.dispatchOrder = dispatchOrder;
        this.toolName = toolName;
        this.status = status;
        this.association = association;
        this.argumentsSummary = argumentsSummary;
        this.observation = observation;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.presentation = presentation;
    }
}
