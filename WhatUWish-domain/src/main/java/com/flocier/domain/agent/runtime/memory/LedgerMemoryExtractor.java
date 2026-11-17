package com.flocier.domain.agent.runtime.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flocier.domain.agent.model.entity.LedgerMemoryBuildCommandEntity;
import com.flocier.domain.agent.model.entity.SessionHistoryMemory;
import com.flocier.domain.agent.model.entity.SessionMemoryView;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.model.execution.ExecutionLedgerView;
import com.flocier.domain.ledger.model.execution.ExecutionLlmCycle;
import com.flocier.domain.ledger.model.execution.ExecutionRunView;
import com.flocier.domain.ledger.model.execution.ExecutionToolInvocation;
import com.flocier.domain.ledger.service.ExecutionLedgerReadService;
import com.flocier.domain.ledger.service.LedgerQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 将 Ledger 的结构化事实投影为连续会话历史。
 *
 * 此处只负责读取、关联和事实降级；字符预算与 prompt 渲染由 MemoryInjectionBudgetPolicy 负责。
 */
@Slf4j
@Component
public class LedgerMemoryExtractor {

    private static final String ARTIFACT_TYPE_LLM_RESPONSE = "llm_response";
    private static final String ARTIFACT_TYPE_TOOL_RESULT = "tool_result";
    private static final String STORAGE_TYPE_INLINE_SUMMARY = "inline_summary";

    private final LedgerQueryService ledgerQueryService;
    private final ExecutionLedgerReadService executionLedgerReadService;
    private final int historyRunLimit;

    /** 供无 Spring 容器的领域测试使用。 */
    public LedgerMemoryExtractor(LedgerQueryService ledgerQueryService,
                                 ExecutionLedgerReadService executionLedgerReadService) {
        this(ledgerQueryService, executionLedgerReadService, 20);
    }

    @Autowired
    public LedgerMemoryExtractor(LedgerQueryService ledgerQueryService,
                                 ExecutionLedgerReadService executionLedgerReadService,
                                 @Value("${memory.ledger.max-history-runs:20}") int historyRunLimit) {
        this.ledgerQueryService = ledgerQueryService;
        this.executionLedgerReadService = executionLedgerReadService;
        this.historyRunLimit = Math.max(1, historyRunLimit);
    }

    /** 构建未裁剪的连续历史视图；不会修改任意 Ledger 原始 payload。 */
    public SessionMemoryView extract(LedgerMemoryBuildCommandEntity command) {
        if (command == null || !StringUtils.hasText(command.getSessionId())) {
            return SessionMemoryView.builder().build();
        }
        ExecutionLedgerView executionView = executionLedgerReadService.readSession(command.getSessionId(), historyRunLimit);
        List<SessionHistoryMemory.RunHistory> runs = executionView.getRuns().stream()
                .filter(item -> item != null && item.getRun() != null)
                .filter(item -> !StringUtils.hasText(command.getCurrentRunId())
                        || !command.getCurrentRunId().equals(item.getRun().getRunId()))
                .map(this::toRunHistory)
                .toList();

        return SessionMemoryView.builder()
                .sessionId(command.getSessionId())
                .currentRunId(command.getCurrentRunId())
                .sessionHistory(new SessionHistoryMemory(command.getSessionId(), runs))
                .build();
    }

    private SessionHistoryMemory.RunHistory toRunHistory(ExecutionRunView view) {
        return SessionHistoryMemory.RunHistory.builder()
                .runId(view.getRun().getRunId())
                .status(view.getRun().getStatus())
                .summary(view.getRun().getSummary())
                .errorCode(view.getRun().getErrorCode())
                .errorMessage(view.getRun().getErrorMessage())
                .cycles(view.getLlmCycles().stream().map(this::toCycleHistory).toList())
                .orphanTools(view.getOrphanToolInvocations().stream().map(this::toToolHistory).toList())
                .unassociatedArtifacts(view.getUnassociatedArtifacts().stream()
                        .filter(this::isVisibleReusableArtifact)
                        .map(this::toArtifactReference)
                        .toList())
                .build();
    }

    private SessionHistoryMemory.CycleHistory toCycleHistory(ExecutionLlmCycle cycle) {
        LlmInvocationLedgerEntity llm = cycle.getLlmInvocation();
        return SessionHistoryMemory.CycleHistory.builder()
                .llmInvocationId(llm.getLlmInvocationId())
                .stepNo(llm.getStepNo())
                .scene(llm.getScene())
                .responseText(llmResponseFact(llm))
                .tools(cycle.getToolInvocations().stream().map(this::toToolHistory).toList())
                .build();
    }

    private SessionHistoryMemory.ToolHistory toToolHistory(ExecutionToolInvocation toolView) {
        ToolInvocationLedgerEntity tool = toolView.getToolInvocation();
        return SessionHistoryMemory.ToolHistory.builder()
                .toolInvocationId(tool.getToolInvocationId())
                .toolCallId(tool.getToolCallId())
                .association(toolView.getAssociation().name())
                .toolName(tool.getToolName())
                .status(tool.getStatus())
                .arguments(toolRequestFact(tool))
                .observation(toolResponseFact(tool))
                .errorCode(tool.getErrorCode())
                .errorMessage(tool.getErrorMessage())
                .artifacts(toolView.getArtifacts().stream()
                        .filter(this::isVisibleReusableArtifact)
                        .map(this::toArtifactReference)
                        .toList())
                .build();
    }

    private SessionHistoryMemory.ArtifactReference toArtifactReference(ArtifactLedgerEntity artifact) {
        return SessionHistoryMemory.ArtifactReference.builder()
                .artifactId(artifact.getArtifactId())
                .name(firstNonBlank(artifact.getFileName(), artifact.getArtifactName()))
                .type(artifact.getArtifactType())
                .storageRef(resolveArtifactRef(artifact))
                .usageSummary(artifact.getContentSummary())
                .build();
    }

    private boolean isVisibleReusableArtifact(ArtifactLedgerEntity artifact) {
        return artifact != null
                && !isLegacyPseudoArtifact(artifact)
                && !"internal".equalsIgnoreCase(defaultText(artifact.getVisibility()))
                && !Integer.valueOf(1).equals(artifact.getDeleted())
                && StringUtils.hasText(resolveArtifactRef(artifact));
    }

    private boolean isLegacyPseudoArtifact(ArtifactLedgerEntity artifact) {
        return ARTIFACT_TYPE_LLM_RESPONSE.equalsIgnoreCase(defaultText(artifact.getArtifactType()))
                || ARTIFACT_TYPE_TOOL_RESULT.equalsIgnoreCase(defaultText(artifact.getArtifactType()))
                || STORAGE_TYPE_INLINE_SUMMARY.equalsIgnoreCase(defaultText(artifact.getStorageType()));
    }

    private String llmResponseFact(LlmInvocationLedgerEntity item) {
        JSONObject response = parsePayloadObject(item == null ? null : item.getResponsePayloadJson());
        return firstNonBlank(
                response == null ? null : response.getString("text"),
                response == null ? null : response.getString("errorMessage"),
                item == null ? null : item.getResponseSummary());
    }

    private String toolResponseFact(ToolInvocationLedgerEntity item) {
        JSONObject response = parsePayloadObject(item == null ? null : item.getResponsePayloadJson());
        return firstNonBlank(
                response == null ? null : response.getString("llmObservation"),
                response == null ? null : response.getString("outputText"),
                response == null ? null : response.getString("errorMessage"),
                item == null ? null : item.getResponseSummary());
    }

    private String toolRequestFact(ToolInvocationLedgerEntity item) {
        JSONObject request = parsePayloadObject(item == null ? null : item.getRequestPayloadJson());
        Object arguments = request == null ? null : request.get("arguments");
        return firstNonBlank(
                arguments == null ? null : JSON.toJSONString(arguments),
                item == null ? null : item.getRequestSummary());
    }

    private JSONObject parsePayloadObject(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return null;
        }
        try {
            return JSON.parseObject(payloadJson);
        } catch (RuntimeException exception) {
            log.warn("ledger memory payload parse failed, fallbackToSummary=true", exception);
            return null;
        }
    }

    private String resolveArtifactRef(ArtifactLedgerEntity artifact) {
        return artifact == null ? "" : firstNonBlank(
                artifact.getStorageRef(), artifact.getStorageKey(), artifact.getDownloadUrl(), artifact.getPreviewUrl());
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private String defaultText(String text) {
        return text == null ? "" : text;
    }
}
