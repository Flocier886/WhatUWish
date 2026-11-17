package com.flocier.domain.agent.model.entity;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 面向 LLM 上下文的会话执行历史投影。
 *
 * 不保存新事实，也不替代 replay；它只保留将 Ledger 事实渲染为连续 Run/Cycle 历史所需的字段。
 */
@Value
public class SessionHistoryMemory {

    String sessionId;
    List<RunHistory> runs;

    @Value
    @Builder
    public static class RunHistory {
        String runId;
        String status;
        String summary;
        String errorCode;
        String errorMessage;
        List<CycleHistory> cycles;
        List<ToolHistory> orphanTools;
        List<ArtifactReference> unassociatedArtifacts;
    }

    @Value
    @Builder
    public static class CycleHistory {
        String llmInvocationId;
        Integer stepNo;
        String scene;
        String responseText;
        List<ToolHistory> tools;
    }

    @Value
    @Builder
    public static class ToolHistory {
        String toolInvocationId;
        String toolCallId;
        String association;
        String toolName;
        String status;
        String arguments;
        String observation;
        String errorCode;
        String errorMessage;
        List<ArtifactReference> artifacts;
    }

    @Value
    @Builder
    public static class ArtifactReference {
        String artifactId;
        String name;
        String type;
        String storageRef;
        String usageSummary;
    }

    @Value
    @Builder
    public static class HistoricalIndexItem {
        String indexType;
        String sourceRunId;
        String sourceId;
        String summary;
        ArtifactReference artifact;
    }
}
