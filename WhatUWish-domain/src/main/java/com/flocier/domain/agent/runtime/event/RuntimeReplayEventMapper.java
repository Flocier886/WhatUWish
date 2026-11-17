package com.flocier.domain.agent.runtime.event;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFileMapper;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.replay.ArtifactReplayEntry;
import com.flocier.domain.ledger.model.replay.CompletedReplayEntry;
import com.flocier.domain.ledger.model.replay.FailedReplayEntry;
import com.flocier.domain.ledger.model.replay.LlmReplayEntry;
import com.flocier.domain.ledger.model.replay.ReplayEntryMetadata;
import com.flocier.domain.ledger.model.replay.ReplayTimelineEntry;
import com.flocier.domain.ledger.model.replay.RunReplayTimeline;
import com.flocier.domain.ledger.model.replay.ToolReplayEntry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 将领域回放时间线显式转换为前端可消费的 Runtime Event v1。 */
@Component
public class RuntimeReplayEventMapper {

    private static final String MISSING_SUMMARY = "该次执行未记录可读的最终结论。";
    private static final String MISSING_OBSERVATION = "该工具调用未记录可展示的 observation。";

    public RuntimeReplayProjection project(RunLedgerEntity run, RunReplayTimeline timeline) {
        List<ReplayTimelineEntry> entries;
        if (timeline == null || timeline.getEntries() == null) {
            entries = List.of();
        } else {
            entries = timeline.getEntries().stream().filter(item -> item != null)
                    .sorted(Comparator.comparingInt((ReplayTimelineEntry item) -> item.getMetadata().getSequence())
                            .thenComparing(item -> item.getMetadata().getEventId()))
                    .toList();
        }
        boolean failed = isFailed(run) || entries.stream().anyMatch(FailedReplayEntry.class::isInstance);
        Map<String, List<String>> artifactIdsByTool = artifactIdsByTool(entries);
        List<String> artifactIds = entries.stream().filter(ArtifactReplayEntry.class::isInstance)
                .map(ArtifactReplayEntry.class::cast).map(ArtifactReplayEntry::getArtifactId)
                .filter(StringUtils::hasText).distinct().toList();
        List<RuntimeEvent> events = new ArrayList<>();
        boolean[] incomplete = {isMissingIdentity(run)};
        long[] sequence = {0};
        boolean terminal = false;

        for (ReplayTimelineEntry entry : entries) {
            if (entry instanceof CompletedReplayEntry && failed) {
                continue;
            }
            if (entry instanceof CompletedReplayEntry && !isCompleted(run)) {
                incomplete[0] = true;
                continue;
            }
            if (entry instanceof ToolReplayEntry tool) {
                events.addAll(toolEvents(run, tool, artifactIdsByTool, incomplete, sequence));
                continue;
            }
            RuntimeEvent event = mapEntry(run, entry, artifactIdsByTool, artifactIds, incomplete, nextSequence(sequence));
            if (event == null) {
                continue;
            }
            if (event.isTerminal()) {
                if (terminal) {
                    incomplete[0] = true;
                    continue;
                }
                terminal = true;
            }
            events.add(event);
        }

        if (!terminal && failed) {
            events.add(failedFallback(run, nextSequence(sequence), incomplete));
        } else if (!terminal && isCompleted(run)) {
            events.add(completedFallback(run, artifactIds, nextSequence(sequence), incomplete));
        } else if (!terminal && isWaiting(run)) {
            events.add(fallbackEvent(run, "waiting:desktop-control", nextSequence(sequence),
                    RuntimeEventType.RUN_WAITING_USER_INPUT, new RunWaitingUserInputPayload()));
        } else if (!terminal) {
            incomplete[0] = true;
        }
        return new RuntimeReplayProjection(List.copyOf(events), incomplete[0]);
    }

    private RuntimeEvent mapEntry(RunLedgerEntity run, ReplayTimelineEntry entry,
                                  Map<String, List<String>> artifactIdsByTool, List<String> artifactIds,
                                  boolean[] incomplete, long sequence) {
        if (entry instanceof LlmReplayEntry llm) {
            return event(run, llm.getMetadata(), sequence, RuntimeEventType.LLM_RESPONSE,
                    new LlmResponsePayload(value(llm.getLlmInvocationId(), "unknown-llm"), null, llm.getModel(), llm.getScene(),
                            RuntimeEventToolPresentation.summary(requiredText(llm.getText(),
                                    "历史记录未保存可展示的模型文本。", incomplete))));
        }
        if (entry instanceof ArtifactReplayEntry artifact) {
            return artifactEvent(run, artifact, sequence);
        }
        if (entry instanceof CompletedReplayEntry completed) {
            return event(run, completed.getMetadata(), sequence, RuntimeEventType.RUN_COMPLETED,
                    new RunCompletedPayload("COMPLETED", RuntimeEventToolPresentation.summary(
                            requiredText(completed.getSummary(), MISSING_SUMMARY, incomplete)),
                            artifactIds, completed.getArtifactSelection()));
        }
        if (entry instanceof FailedReplayEntry failed) {
            return event(run, failed.getMetadata(), sequence, RuntimeEventType.RUN_FAILED,
                    new RunFailedPayload("FAILED", failed.getErrorCode(),
                            requiredText(failed.getErrorMessage(), "运行失败，但未记录具体错误。", incomplete)));
        }
        incomplete[0] = true;
        return null;
    }

    private List<RuntimeEvent> toolEvents(RunLedgerEntity run, ToolReplayEntry tool,
                                          Map<String, List<String>> artifactIdsByTool,
                                          boolean[] incomplete, long[] sequence) {
        String toolInvocationId = value(tool.getToolInvocationId(), "unknown-tool-invocation");
        String toolName = requiredText(tool.getToolName(), "unknown_tool", incomplete);
        String association = value(tool.getAssociation(), "ORPHAN");
        if (tool.getStartedAt() == null) {
            incomplete[0] = true;
        }
        List<RuntimeEvent> events = new ArrayList<>();
        events.add(toolEvent(run, tool, nextSequence(sequence), "tool:" + toolInvocationId + ":started",
                occurredAt(tool.getStartedAt(), run), RuntimeEventType.TOOL_STARTED,
                new ToolStartedPayload(toolInvocationId, tool.getToolCallId(), tool.getLlmInvocationId(), tool.getStepNo(),
                        tool.getDispatchOrder(), toolName, "RUNNING", association,
                        RuntimeEventToolPresentation.arguments(toolName, tool.getArgumentsSummary()))));

        String status = terminalToolStatus(tool.getStatus(), tool.getFinishedAt(), incomplete);
        if (status == null) {
            if (isTerminal(run)) {
                incomplete[0] = true;
            }
            return List.copyOf(events);
        }
        events.add(toolEvent(run, tool, nextSequence(sequence), tool.getMetadata().getEventId(),
                occurredAt(tool.getFinishedAt(), run), RuntimeEventType.TOOL_RESULT,
                new ToolResultPayload(toolInvocationId, tool.getToolCallId(), tool.getLlmInvocationId(), tool.getStepNo(),
                        tool.getDispatchOrder(), toolName, status, association,
                        RuntimeEventToolPresentation.arguments(toolName, tool.getArgumentsSummary()),
                        RuntimeEventToolPresentation.observation(toolName,
                                requiredText(tool.getObservation(), MISSING_OBSERVATION, incomplete)),
                        tool.getErrorCode(), RuntimeEventToolPresentation.errorMessage(tool.getErrorMessage()),
                        artifactIdsByTool.getOrDefault(toolInvocationId, List.of()), tool.getPresentation())));
        return List.copyOf(events);
    }

    private RuntimeEvent toolEvent(RunLedgerEntity run, ToolReplayEntry tool, long sequence, String eventId,
                                   Instant occurredAt, RuntimeEventType type, RuntimeEventPayload payload) {
        return new RuntimeEvent(RuntimeEvent.SCHEMA_VERSION_V1,
                "history:" + runId(run) + ":" + value(eventId, type.name().toLowerCase(Locale.ROOT)), sequence,
                type, occurredAt, sessionId(run), runId(run), requestId(run), agentId(run), payload);
    }

    private String terminalToolStatus(String status, Instant finishedAt, boolean[] incomplete) {
        if ("RUNNING".equalsIgnoreCase(status) || "IN_PROGRESS".equalsIgnoreCase(status)) {
            return null;
        }
        if (!StringUtils.hasText(status)) {
            if (finishedAt == null) {
                return null;
            }
            incomplete[0] = true;
            return "FAILED";
        }
        if ("SUCCESS".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status)
                || "RECORDED".equalsIgnoreCase(status) || "WAITING_USER_INPUT".equalsIgnoreCase(status)) {
            return "SUCCEEDED";
        }
        if ("PARTIAL_SUCCESS".equalsIgnoreCase(status) || "PARTIAL-SUCCESS".equalsIgnoreCase(status)) {
            return "PARTIAL_SUCCESS";
        }
        return "FAILED";
    }

    private RuntimeEvent artifactEvent(RunLedgerEntity run, ArtifactReplayEntry artifact, long sequence) {
        ArtifactDeliveryFile delivery = ArtifactDeliveryFileMapper.fromReplay(artifact);
        String artifactId = value(delivery.getArtifactId(), "unknown-artifact");
        return event(run, artifact.getMetadata(), sequence, RuntimeEventType.ARTIFACT_CREATED,
                new ArtifactCreatedPayload(artifactId, artifactId, value(delivery.getName(), "未命名产物"),
                        value(delivery.getArtifactType(), "artifact"), delivery.getMimeType(), delivery.getSize(), delivery.getSummary(),
                        visibility(artifact.getVisibility()), value(artifact.getToolInvocationId(), artifact.getSourceToolInvocationId()),
                        delivery.getSourceStage(), delivery.getAccessState().name(),
                        delivery.getPreview(), delivery.isPreviewSupported(), delivery.isDownloadSupported()));
    }

    private RuntimeEvent completedFallback(RunLedgerEntity run, List<String> artifactIds, long sequence, boolean[] incomplete) {
        incomplete[0] = true;
        return fallbackEvent(run, "final:fallback", sequence, RuntimeEventType.RUN_COMPLETED,
                new RunCompletedPayload("COMPLETED", RuntimeEventToolPresentation.summary(
                        value(run == null ? null : run.getSummary(), MISSING_SUMMARY)), artifactIds, "fallback"));
    }

    private RuntimeEvent failedFallback(RunLedgerEntity run, long sequence, boolean[] incomplete) {
        incomplete[0] = true;
        return fallbackEvent(run, "failure:fallback", sequence, RuntimeEventType.RUN_FAILED,
                new RunFailedPayload("FAILED", run == null ? null : run.getErrorCode(),
                        value(run == null ? null : run.getErrorMessage(), "运行失败，但未记录具体错误。")));
    }

    private RuntimeEvent event(RunLedgerEntity run, ReplayEntryMetadata metadata, long sequence,
                               RuntimeEventType type, RuntimeEventPayload payload) {
        return new RuntimeEvent(RuntimeEvent.SCHEMA_VERSION_V1,
                "history:" + runId(run) + ":" + value(metadata.getEventId(), type.name().toLowerCase(Locale.ROOT)), sequence,
                type, occurredAt(metadata.getOccurredAt(), run), sessionId(run), runId(run), requestId(run), agentId(run), payload);
    }

    private RuntimeEvent fallbackEvent(RunLedgerEntity run, String suffix, long sequence,
                                       RuntimeEventType type, RuntimeEventPayload payload) {
        return new RuntimeEvent(RuntimeEvent.SCHEMA_VERSION_V1, "history:" + runId(run) + ":" + suffix, sequence,
                type, occurredAt(null, run), sessionId(run), runId(run), requestId(run), agentId(run), payload);
    }

    private Map<String, List<String>> artifactIdsByTool(List<ReplayTimelineEntry> entries) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ReplayTimelineEntry entry : entries) {
            if (!(entry instanceof ArtifactReplayEntry artifact) || !StringUtils.hasText(artifact.getToolInvocationId())
                    || !StringUtils.hasText(artifact.getArtifactId())) {
                continue;
            }
            result.computeIfAbsent(artifact.getToolInvocationId(), ignored -> new ArrayList<>()).add(artifact.getArtifactId());
        }
        return result;
    }

    private long nextSequence(long[] current) { return ++current[0]; }
    private boolean isFailed(RunLedgerEntity run) { return run != null && "FAILED".equalsIgnoreCase(run.getStatus()); }
    private boolean isCompleted(RunLedgerEntity run) {
        return run != null && ("COMPLETED".equalsIgnoreCase(run.getStatus()) || "SUCCESS".equalsIgnoreCase(run.getStatus()));
    }
    private boolean isWaiting(RunLedgerEntity run) {
        return run != null && "WAITING_USER_INPUT".equalsIgnoreCase(run.getStatus());
    }
    private boolean isTerminal(RunLedgerEntity run) { return isCompleted(run) || isFailed(run) || isWaiting(run); }
    private boolean isMissingIdentity(RunLedgerEntity run) {
        return run == null || !StringUtils.hasText(run.getSessionId()) || !StringUtils.hasText(run.getRunId())
                || !StringUtils.hasText(run.getRequestId()) || !StringUtils.hasText(run.getAgentId());
    }
    private Instant occurredAt(Instant occurredAt, RunLedgerEntity run) {
        if (occurredAt != null) return occurredAt;
        if (run != null && run.getFinishedAt() != null) return run.getFinishedAt();
        if (run != null && run.getStartedAt() != null) return run.getStartedAt();
        return Instant.EPOCH;
    }
    private String sessionId(RunLedgerEntity run) { return value(run == null ? null : run.getSessionId(), "unknown-session"); }
    private String runId(RunLedgerEntity run) { return value(run == null ? null : run.getRunId(), "unknown-run"); }
    private String requestId(RunLedgerEntity run) { return value(run == null ? null : run.getRequestId(), runId(run)); }
    private String agentId(RunLedgerEntity run) { return value(run == null ? null : run.getAgentId(), "unknown-agent"); }
    private String requiredText(String value, String fallback, boolean[] incomplete) {
        if (StringUtils.hasText(value)) return value;
        incomplete[0] = true;
        return fallback;
    }
    private String visibility(String value) { return "visible".equalsIgnoreCase(value) ? "VISIBLE" : "RESTRICTED"; }
    private String value(String value, String fallback) { return StringUtils.hasText(value) ? value : fallback; }
}
