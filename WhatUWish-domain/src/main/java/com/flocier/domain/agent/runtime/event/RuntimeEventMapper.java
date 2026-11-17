package com.flocier.domain.agent.runtime.event;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.canvas.CanvasPublishPresentation;
import com.flocier.domain.agent.runtime.agent.tool.genui.GenUiPresentation;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFileMapper;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** 将已确认 ledger 事实映射为前端可消费的安全事件草稿。 */
@Component
public class RuntimeEventMapper {

    public RuntimeEventDraft runStarted(RunLedgerEntity run, AgentContext context) {
        return draft(RuntimeEventType.RUN_STARTED, run, run.getStartedAt(),
                new RunStartedPayload("RUNNING", context == null ? null : context.getTask()));
    }

    public RuntimeEventDraft runFinished(RunLedgerEntity run) {
        return runFinished(run, List.of());
    }

    public RuntimeEventDraft runFinished(RunLedgerEntity run, List<String> artifactIds) {
        if ("WAITING_USER_INPUT".equalsIgnoreCase(run.getStatus()))
            return draft(RuntimeEventType.RUN_WAITING_USER_INPUT, run, run.getFinishedAt(), new RunWaitingUserInputPayload());
        boolean failed = "FAILED".equalsIgnoreCase(run.getStatus());
        if (failed) {
            return draft(RuntimeEventType.RUN_FAILED, run, run.getFinishedAt(),
                    new RunFailedPayload("FAILED", run.getErrorCode(), firstNonBlank(run.getErrorMessage(), "运行失败")));
        }
        return draft(RuntimeEventType.RUN_COMPLETED, run, run.getFinishedAt(),
                new RunCompletedPayload("COMPLETED", RuntimeEventToolPresentation.summary(
                        firstNonBlank(run.getSummary(), "运行完成")), artifactIds, null));
    }

    public RuntimeEventDraft llmResponse(LlmInvocationLedgerEntity invocation, LlmResponse response) {
        return llmResponse(invocation, response == null ? null : response.getText());
    }

    public RuntimeEventDraft llmResponse(LlmInvocationLedgerEntity invocation, String text) {
        return new RuntimeEventDraft(RuntimeEventType.LLM_RESPONSE, instant(invocation.getFinishedAt()),
                invocation.getSessionId(), invocation.getRunId(), invocation.getRequestId(),
                agentId(invocation.getExt()), new LlmResponsePayload(invocation.getLlmInvocationId(), invocation.getStepNo(),
                invocation.getModel(), invocation.getScene(), RuntimeEventToolPresentation.summary(
                        firstNonBlank(text, invocation.getResponseSummary()))));
    }

    public RuntimeEventDraft toolResult(ToolInvocationLedgerEntity invocation, ToolExecutionResult result) {
        String observation = firstNonBlank(result == null ? null : result.getLlmObservation(),
                result == null ? null : result.getPreferredObservationText(), invocation.getResponseSummary());
        String status = presentationStatus(invocation.getStatus(), result == null ? null : result.getStatus());
        return new RuntimeEventDraft(RuntimeEventType.TOOL_RESULT, instant(invocation.getFinishedAt()),
                invocation.getSessionId(), invocation.getRunId(), invocation.getRequestId(), agentId(invocation.getExt()),
                new ToolResultPayload(invocation.getToolInvocationId(), invocation.getToolCallId(), invocation.getLlmInvocationId(),
                        invocation.getStepNo(), invocation.getDispatchOrder(), invocation.getToolName(), status,
                        invocation.getLlmInvocationId() == null ? "ORPHAN" : "PERSISTED",
                        RuntimeEventToolPresentation.arguments(invocation.getToolName(), invocation.getRequestSummary()),
                        RuntimeEventToolPresentation.observation(invocation.getToolName(),
                                firstNonBlank(observation, "工具未返回可展示 observation")),
                        invocation.getErrorCode(), RuntimeEventToolPresentation.errorMessage(invocation.getErrorMessage()), List.of(),
                        "SUCCEEDED".equals(status) ? toolPresentation(invocation.getToolName(), result) : null));
    }

    public RuntimeEventDraft toolStarted(ToolInvocationLedgerEntity invocation) {
        if (invocation == null || StringUtils.isBlank(invocation.getToolInvocationId())
                || !"RUNNING".equalsIgnoreCase(invocation.getStatus())) {
            return null;
        }
        return new RuntimeEventDraft(RuntimeEventType.TOOL_STARTED, instant(invocation.getStartedAt()),
                invocation.getSessionId(), invocation.getRunId(), invocation.getRequestId(), agentId(invocation.getExt()),
                new ToolStartedPayload(invocation.getToolInvocationId(), invocation.getToolCallId(),
                        invocation.getLlmInvocationId(), invocation.getStepNo(), invocation.getDispatchOrder(), invocation.getToolName(), "RUNNING",
                        invocation.getLlmInvocationId() == null ? "ORPHAN" : "PERSISTED",
                        RuntimeEventToolPresentation.arguments(invocation.getToolName(), invocation.getRequestSummary())));
    }

    public RuntimeEventDraft artifactCreated(ArtifactLedgerEntity artifact, String agentId) {
        String reference = firstNonBlank(artifact.getSourceId(), artifact.getArtifactId());
        ArtifactDeliveryFile delivery = ArtifactDeliveryFileMapper.fromLedger(artifact,
                ArtifactAccessState.AVAILABLE, true);
        return new RuntimeEventDraft(RuntimeEventType.ARTIFACT_CREATED, instant(artifact.getCreatedAt()),
                artifact.getSessionId(), artifact.getRunId(), artifact.getRequestId(), agentId,
                new ArtifactCreatedPayload(delivery.getArtifactId(), reference, delivery.getName(),
                        delivery.getArtifactType(), delivery.getMimeType(), delivery.getSize(), delivery.getSummary(),
                        normalizeVisibility(artifact.getVisibility()), artifact.getToolInvocationId(),
                        delivery.getSourceStage(), delivery.getAccessState().name(),
                        delivery.getPreview(), delivery.isPreviewSupported(), delivery.isDownloadSupported()));
    }

    public RuntimeEventDraft status(AgentContext context, String message) {
        return new RuntimeEventDraft(RuntimeEventType.RUN_STATUS, Instant.now(), context.getSessionId(),
                stringAttribute(context, "runId"), context.getRequestId(), stringAttribute(context, "agentId"),
                new RunStatusPayload("RUNNING", message));
    }

    private RuntimeEventDraft draft(RuntimeEventType type, RunLedgerEntity run, Instant occurredAt, RuntimeEventPayload payload) {
        return new RuntimeEventDraft(type, instant(occurredAt), run.getSessionId(), run.getRunId(), run.getRequestId(),
                run.getAgentId(), payload);
    }

    private String agentId(java.util.Map<String, Object> ext) {
        if (ext == null) return null;
        Object value = ext.get("agentId");
        return value == null ? null : String.valueOf(value);
    }

    private String stringAttribute(AgentContext context, String key) {
        Object value = context == null ? null : context.getAttribute(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean isSucceeded(String invocationStatus, String resultStatus) {
        String status = firstNonBlank(invocationStatus, resultStatus);
        return "SUCCESS".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status)
                || "RECORDED".equalsIgnoreCase(status) || "WAITING_USER_INPUT".equalsIgnoreCase(status);
    }

    private String presentationStatus(String invocationStatus, String resultStatus) {
        String status = firstNonBlank(invocationStatus, resultStatus);
        if ("PARTIAL_SUCCESS".equalsIgnoreCase(status) || "PARTIAL-SUCCESS".equalsIgnoreCase(status)) {
            return "PARTIAL_SUCCESS";
        }
        return isSucceeded(invocationStatus, resultStatus) ? "SUCCEEDED" : "FAILED";
    }

    private java.util.Map<String, Object> toolPresentation(String toolName, ToolExecutionResult result) {
        Object output = result == null ? null : result.getStructuredOutput();
        if ("canvas_publish".equals(toolName)) return CanvasPublishPresentation.fromStructured(output);
        return GenUiPresentation.fromStructured(toolName, output);
    }

    private String normalizeVisibility(String visibility) {
        return "visible".equalsIgnoreCase(visibility) ? "VISIBLE" : "RESTRICTED";
    }
    private Instant instant(Instant value) { return value == null ? Instant.now() : value; }
    private String firstNonBlank(String... values) { return StringUtils.firstNonBlank(values); }
}
