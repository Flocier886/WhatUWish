package com.flocier.domain.ledger.service.replay;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flocier.domain.agent.runtime.artifact.ArtifactReplayResolver;
import com.flocier.domain.agent.runtime.artifact.ArtifactReplayResult;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewPolicy;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.execution.ExecutionLlmCycle;
import com.flocier.domain.ledger.model.execution.ExecutionRunView;
import com.flocier.domain.ledger.model.execution.ExecutionToolInvocation;
import com.flocier.domain.ledger.model.replay.ArtifactReplayEntry;
import com.flocier.domain.ledger.model.replay.CompletedReplayEntry;
import com.flocier.domain.ledger.model.replay.FailedReplayEntry;
import com.flocier.domain.ledger.model.replay.LlmReplayEntry;
import com.flocier.domain.ledger.model.replay.ReplayEntryMetadata;
import com.flocier.domain.ledger.model.replay.ReplayTimelineEntry;
import com.flocier.domain.ledger.model.replay.RunReplayTimeline;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 将执行结构投影为有序展示事件，整个过程只读。 */
@Component
@RequiredArgsConstructor
public class ReplayProjector {
    private final ToolReplayProjectorRegistry toolProjectorRegistry;
    private final ArtifactReplayResolver artifactReplayResolver;
    private final ArtifactContentStore artifactContentStore;

    public RunReplayTimeline project(ExecutionRunView view) {
        RunLedgerEntity run = view.getRun();
        List<ReplayTimelineEntry> entries = new ArrayList<>();
        int[] sequence = {0};
        List<ArtifactLedgerEntity> allArtifacts = new ArrayList<>(view.getUnassociatedArtifacts());
        for (ExecutionLlmCycle cycle : view.getLlmCycles()) {
            entries.add(projectLlm(cycle.getLlmInvocation(), ++sequence[0]));
            for (ExecutionToolInvocation tool : cycle.getToolInvocations()) {
                entries.add(toolProjectorRegistry.resolve(tool.getToolInvocation().getToolName()).project(tool, ++sequence[0]));
                allArtifacts.addAll(tool.getArtifacts());
                tool.getArtifacts().stream().filter(this::isReplayVisible)
                        .forEach(artifact -> entries.add(projectArtifact(artifact, tool, ++sequence[0])));
            }
        }
        for (ExecutionToolInvocation tool : view.getOrphanToolInvocations()) {
            entries.add(toolProjectorRegistry.resolve(tool.getToolInvocation().getToolName()).project(tool, ++sequence[0]));
            allArtifacts.addAll(tool.getArtifacts());
            tool.getArtifacts().stream().filter(this::isReplayVisible)
                    .forEach(artifact -> entries.add(projectArtifact(artifact, tool, ++sequence[0])));
        }
        view.getUnassociatedArtifacts().stream().filter(this::isReplayVisible)
                .forEach(artifact -> entries.add(projectArtifact(artifact, null, ++sequence[0])));
        appendTerminalEntries(entries, run, allArtifacts, sequence);
        return RunReplayTimeline.builder().sessionId(run.getSessionId()).runId(run.getRunId()).entries(List.copyOf(entries)).build();
    }

    private LlmReplayEntry projectLlm(LlmInvocationLedgerEntity llm, int sequence) {
        return new LlmReplayEntry(new ReplayEntryMetadata("llm:" + llm.getLlmInvocationId(), sequence,
                llm.getFinishedAt() == null ? llm.getStartedAt() : llm.getFinishedAt(), llm.getRunId(), false),
                llm.getLlmInvocationId(), llm.getModel(), llm.getScene(), llmText(llm));
    }

    private ArtifactReplayEntry projectArtifact(ArtifactLedgerEntity artifact, ExecutionToolInvocation tool, int sequence) {
        String toolInvocationId = tool == null ? artifact.getToolInvocationId() : tool.getToolInvocation().getToolInvocationId();
        ArtifactAccessState accessState = currentAccessState(artifact);
        boolean available = accessState == ArtifactAccessState.AVAILABLE;
        ArtifactPreviewDescriptor preview = declaredPreview(artifact);
        boolean previewSupported = ArtifactPreviewPolicy.describe(
                accessState,
                artifact.getMimeType(), artifact.getFileName(),
                artifact.getFileSize() == null ? artifact.getSize() : artifact.getFileSize(), preview
        ) != null;
        return new ArtifactReplayEntry(new ReplayEntryMetadata("artifact:" + artifact.getArtifactId(), sequence,
                artifact.getCreatedAt(), artifact.getRunId(), false),
                tool == null ? null : tool.getParentLlmInvocationId(), toolInvocationId, artifact.getArtifactId(),
                firstNonBlank(artifact.getArtifactName(), artifact.getFileName(), "未命名产物"), artifact.getArtifactType(),
                artifact.getMimeType(), artifact.getFileSize() == null ? artifact.getSize() : artifact.getFileSize(),
                artifact.getContentSummary(), artifact.getVisibility(), toolInvocationId, artifact.getSourceName(),
                accessState.name(),
                previewSupported, available, preview);
    }

    /**
     * 历史事实仍保留 Artifact 身份，但展示 capability 必须以当前存储状态为准，
     * 不能因为账本行尚未软删除就向前端承诺一个已经无法读取的文件。
     */
    private ArtifactAccessState currentAccessState(ArtifactLedgerEntity artifact) {
        if (Integer.valueOf(1).equals(artifact.getDeleted())) {
            return ArtifactAccessState.NOT_FOUND;
        }
        ArtifactContentLocator locator = new ArtifactContentLocator(artifact.getStorageType(),
                artifact.getStorageKey(), artifact.getStorageRef());
        if (locator.isEmpty()) {
            return ArtifactAccessState.UNAVAILABLE;
        }
        try {
            artifactContentStore.inspect(locator);
            return ArtifactAccessState.AVAILABLE;
        } catch (RuntimeException ignored) {
            return ArtifactAccessState.UNAVAILABLE;
        }
    }

    private void appendTerminalEntries(List<ReplayTimelineEntry> entries, RunLedgerEntity run,
                                      List<ArtifactLedgerEntity> artifacts, int[] sequence) {
        ArtifactReplayResult result = artifactReplayResolver.resolve(run.getSummary(), artifacts);
        if ("FAILED".equalsIgnoreCase(run.getStatus())) {
            entries.add(new FailedReplayEntry(new ReplayEntryMetadata("failure:" + run.getRunId(), ++sequence[0],
                    run.getFinishedAt(), run.getRunId(), !StringUtils.hasText(run.getErrorMessage())),
                    run.getErrorCode(), run.getErrorMessage()));
            return;
        }
        String finalText = firstNonBlank(result.getSummaryText(), run.getSummary(), run.getErrorMessage(), "该次执行未产生可读最终结论");
        entries.add(new CompletedReplayEntry(new ReplayEntryMetadata("final:" + run.getRunId(), ++sequence[0],
                run.getFinishedAt(), run.getRunId(), !StringUtils.hasText(run.getSummary())),
                finalText, result.isExplicitSelection() ? "explicit" : "fallback"));
    }

    private String llmText(LlmInvocationLedgerEntity llm) {
        try {
            JSONObject response = JSON.parseObject(llm.getResponsePayloadJson());
            String text = response == null ? null : response.getString("text");
            return StringUtils.hasText(text) ? text : llm.getResponseSummary();
        } catch (RuntimeException ignored) {
            return llm.getResponseSummary();
        }
    }

    private boolean isReplayVisible(ArtifactLedgerEntity artifact) {
        return artifact != null
                && "visible".equalsIgnoreCase(artifact.getVisibility())
                && "output".equalsIgnoreCase(artifact.getArtifactRole())
                && !"llm_response".equalsIgnoreCase(artifact.getArtifactType())
                && !"tool_result".equalsIgnoreCase(artifact.getArtifactType())
                && !"inline_summary".equalsIgnoreCase(artifact.getStorageType());
    }

    private ArtifactPreviewDescriptor declaredPreview(ArtifactLedgerEntity artifact) {
        try {
            JSONObject metadata = JSON.parseObject(artifact.getMetadataJson());
            if (metadata == null || !(metadata.get("preview") instanceof java.util.Map<?, ?> preview)) {
                return null;
            }
            Object kind = preview.get("kind");
            Object schemaVersion = preview.get("schemaVersion");
            return kind instanceof String && schemaVersion instanceof String
                    ? new ArtifactPreviewDescriptor((String) kind, (String) schemaVersion)
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values != null) for (String value : values) if (StringUtils.hasText(value)) return value;
        return "";
    }
}
