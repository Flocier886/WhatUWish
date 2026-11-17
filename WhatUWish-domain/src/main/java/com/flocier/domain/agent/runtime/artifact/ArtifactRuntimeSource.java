package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import lombok.Builder;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;

/** 当前一次工具调用产生 artifact 的运行期来源事实。 */
@Value
@Builder
public class ArtifactRuntimeSource {
    String runId;
    String sessionId;
    String requestId;
    String traceId;
    Integer step;
    String toolCallId;
    String toolName;

    public String sourceKey() {
        return String.join("|",
                StringUtils.defaultString(runId),
                StringUtils.defaultString(requestId),
                StringUtils.defaultString(toolCallId),
                StringUtils.defaultString(toolName));
    }

    public static ArtifactRuntimeSource from(AgentContext context, ToolInvokeRequest request, String toolName) {
        return ArtifactRuntimeSource.builder()
                .runId(attribute(context, "runId"))
                .sessionId(firstNonBlank(request == null ? null : request.getSessionId(), context == null ? null : context.getSessionId()))
                .requestId(firstNonBlank(request == null ? null : request.getRequestId(), context == null ? null : context.getRequestId()))
                .traceId(context == null ? null : context.getTraceId())
                .step(request == null ? (context == null ? null : context.getCurrentStep()) : request.getStep())
                .toolCallId(request == null ? null : request.getToolCallId())
                .toolName(firstNonBlank(toolName, request == null ? null : request.getToolName()))
                .build();
    }

    private static String attribute(AgentContext context, String key) {
        if (context == null) {
            return null;
        }
        Object value = context.getAttribute(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
