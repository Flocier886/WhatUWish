package com.flocier.domain.ledger.service.replay;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flocier.domain.agent.runtime.agent.tool.canvas.CanvasPublishPresentation;
import com.flocier.domain.agent.runtime.agent.tool.genui.GenUiPresentation;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.model.execution.ExecutionToolInvocation;
import com.flocier.domain.ledger.model.replay.ReplayEntryMetadata;
import com.flocier.domain.ledger.model.replay.ToolReplayEntry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 普通工具的受控回放投影，不暴露完整 request/response payload。 */
@Component
public class DefaultToolReplayProjector implements ToolReplayProjector {
    @Override
    public boolean supports(String toolName) {
        return true;
    }

    @Override
    public ToolReplayEntry project(ExecutionToolInvocation view, int sequence) {
        ToolInvocationLedgerEntity tool = view.getToolInvocation();
        String observation = observation(tool);
        boolean failed = !"SUCCESS".equalsIgnoreCase(tool.getStatus()) && !"SUCCEEDED".equalsIgnoreCase(tool.getStatus());
        return new ToolReplayEntry(
                new ReplayEntryMetadata("tool:" + tool.getToolInvocationId(), sequence,
                        tool.getFinishedAt() == null ? tool.getStartedAt() : tool.getFinishedAt(), tool.getRunId(),
                        view.getAssociation().name().equals("ORPHAN")),
                view.getParentLlmInvocationId(), tool.getToolInvocationId(), tool.getToolCallId(), tool.getStepNo(), tool.getDispatchOrder(),
                tool.getToolName(), tool.getStatus(), view.getAssociation().name(), controlledArguments(tool), observation,
                failed ? tool.getErrorCode() : null, failed ? tool.getErrorMessage() : null,
                tool.getStartedAt(), tool.getFinishedAt(),
                failed ? null : presentation(tool));
    }

    private String controlledArguments(ToolInvocationLedgerEntity tool) {
        try {
            JSONObject request = JSON.parseObject(tool.getRequestPayloadJson());
            if (request == null || !request.containsKey("arguments")) {
                return tool.getRequestSummary();
            }
            Object arguments = request.get("arguments");
            return arguments instanceof String ? (String) arguments : JSON.toJSONString(arguments);
        } catch (RuntimeException ignored) {
            return tool.getRequestSummary();
        }
    }

    private java.util.Map<String, Object> presentation(ToolInvocationLedgerEntity tool) {
        if ("canvas_publish".equals(tool.getToolName()))
            return CanvasPublishPresentation.fromLedger(tool.getResponsePayloadJson());
        return GenUiPresentation.fromLedger(tool.getToolName(), tool.getResponsePayloadJson());
    }

    private String observation(ToolInvocationLedgerEntity tool) {
        try {
            JSONObject response = JSON.parseObject(tool.getResponsePayloadJson());
            if (response != null && StringUtils.hasText(response.getString("llmObservation"))) {
                return response.getString("llmObservation");
            }
        } catch (RuntimeException ignored) {
            // 历史 payload 不可读时受控降级为摘要字段。
        }
        return tool.getResponseSummary();
    }
}
