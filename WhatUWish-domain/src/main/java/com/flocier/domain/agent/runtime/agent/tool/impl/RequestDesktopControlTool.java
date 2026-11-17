package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopSessionPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型合同沿用 master，桌面 owner 改为本站 3A 的 code:sessionId。 */
public class RequestDesktopControlTool implements BaseTool {
    public static final String NAME = "RequestDesktopControl";
    public static final String OPENED_ATTRIBUTE = "desktopControl.opened";
    private final DesktopSessionPort desktop;

    public RequestDesktopControlTool(DesktopSessionPort desktop) { this.desktop = desktop; }
    @Override public String name() { return NAME; }
    @Override public String description() {
        return "把当前会话的图形桌面交给用户操作，适用于登录、点按钮、过验证码等需要人手的界面。"
                + "调用后当前 run 结束，等用户点「我已完成」再继续。必须是本轮唯一 tool call。"
                + "不要用它替代 code_execution。完成后自己再检查结果。仅主 Agent 可用。";
    }
    @Override public Object params() {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "给用户看的一句话，说明为什么需要操作桌面");
        return Map.of("type", "object", "properties", Map.of("reason", reason), "required", List.of("reason"));
    }
    @Override public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request))
            return ToolResultPayload.failure("desktop_request_missing", "缺少工具调用上下文", "无法打开桌面");
        Object raw = request.getArguments().get("reason");
        String reason = raw == null ? "" : String.valueOf(raw).trim();
        if (reason.isBlank()) return ToolResultPayload.failure("desktop_reason_missing", "reason 不能为空", "请说明需要用户操作的原因");
        try {
            String sessionId = context.getSessionId();
            DesktopSessionPort.OpenedDesktop opened = desktop.open(context.getRequestId(), sessionId,
                    "code:" + sessionId, 600);
            context.setAttribute(OPENED_ATTRIBUTE, opened);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reason", reason);
            return ToolResultPayload.builder().success(true).status("waiting_user_input")
                    .toolResult("等待用户操作桌面").llmObservation("桌面已交给用户，等待用户完成。")
                    .structuredOutput(result).build();
        } catch (Exception failure) {
            return ToolResultPayload.failure("desktop_open_failed", failure.getMessage(),
                    "打开桌面失败：" + failure.getMessage());
        }
    }
}
