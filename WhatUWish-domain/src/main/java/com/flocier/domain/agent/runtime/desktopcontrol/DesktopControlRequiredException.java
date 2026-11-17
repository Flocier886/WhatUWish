package com.flocier.domain.agent.runtime.desktopcontrol;

/** 工具事实已写入后，通知主工作流以等待输入结束本次 Run。 */
public class DesktopControlRequiredException extends RuntimeException {
    private final String reason;
    private final String streamUrl;
    private final double holdUntil;
    private final String toolCallId;

    public DesktopControlRequiredException(String reason, String streamUrl, double holdUntil, String toolCallId) {
        super("等待用户完成桌面操作");
        this.reason = reason;
        this.streamUrl = streamUrl;
        this.holdUntil = holdUntil;
        this.toolCallId = toolCallId;
    }
    public String getReason() { return reason; }
    public String getStreamUrl() { return streamUrl; }
    public double getHoldUntil() { return holdUntil; }
    public String getToolCallId() { return toolCallId; }
}
