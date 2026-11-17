package com.flocier.domain.agent.adapter.port;

/** Kernel 会话的领域出口；工具与 HTTP 入口通过它共享同一 owner 的浏览器状态。 */
public interface AgentBrowserSessionPort {

    boolean isConfigured();

    /** 返回可供 OpenCLI 子进程使用的当前 CDP 地址；失效会话由适配器恢复或重建。 */
    AgentBrowserSession resolveForUser(String userId);

    AgentBrowserSessionStatus statusForUser(String userId);

    /** 确保浏览器存在并返回供前端打开的实时画面地址。 */
    AgentBrowserLiveView ensureLiveView(String userId);

    void deleteForUser(String userId);
}
