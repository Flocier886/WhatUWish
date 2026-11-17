package com.flocier.application.agent.runtimeevent;

/**
 * 面向运行时入口发布的 Agent 描述。
 *
 * <p>它表示可被当前 Runtime Event API 启动的能力，不等同于旧 {@code ai_agent}
 * 数据表中的运营配置。前端只能依据该目录选择可启动的运行时能力。</p>
 */
public final class RuntimeAgentDescriptor {

    private final String agentId;
    private final String displayName;
    private final String description;
    private final String runtimeKind;
    private final boolean available;

    public RuntimeAgentDescriptor(String agentId, String displayName, String description, String runtimeKind,
                                  boolean available) {
        this.agentId = agentId;
        this.displayName = displayName;
        this.description = description;
        this.runtimeKind = runtimeKind;
        this.available = available;
    }

    public String getAgentId() { return agentId; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getRuntimeKind() { return runtimeKind; }
    public boolean isAvailable() { return available; }
}
