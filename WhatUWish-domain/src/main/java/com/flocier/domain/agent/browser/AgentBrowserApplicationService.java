package com.flocier.domain.agent.browser;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.flocier.domain.agent.adapter.port.AgentBrowserLiveView;
import com.flocier.domain.agent.adapter.port.AgentBrowserSessionPort;
import com.flocier.domain.agent.adapter.port.AgentBrowserSessionStatus;


/** 前端会话操作的应用入口；真实远端调用和映射持久化由 Kernel Port 承担。 */
@Service
public class AgentBrowserApplicationService {

    private final AgentBrowserSessionPort agentBrowserSessionPort;

    public AgentBrowserApplicationService(ObjectProvider<AgentBrowserSessionPort> portProvider) {
        this.agentBrowserSessionPort = portProvider.getIfAvailable();
    }

    /** 启动或恢复当前 owner 的浏览器，并取得可打开的实时画面。 */
    public AgentBrowserLiveView ensure(String userId) {
        AgentBrowserSessionPort port = requireConfiguredPort(userId);
        return port.ensureLiveView(userId.trim());
    }

    /** 未启用 Kernel 时只返回“不存在”，状态查询本身不创建远端浏览器。 */
    public AgentBrowserSessionStatus status(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (agentBrowserSessionPort == null || !agentBrowserSessionPort.isConfigured()) {
            return AgentBrowserSessionStatus.builder()
                    .exists(false)
                    .reconstructed(false)
                    .build();
        }
        return agentBrowserSessionPort.statusForUser(userId);
    }

    /** 重置当前 owner 的远端浏览器及本地会话映射。 */
    public void reset(String userId) {
        AgentBrowserSessionPort port = requireConfiguredPort(userId);
        port.deleteForUser(userId.trim());
    }

    private AgentBrowserSessionPort requireConfiguredPort(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (agentBrowserSessionPort == null || !agentBrowserSessionPort.isConfigured()) {
            throw new IllegalStateException("Agent 浏览器未配置，请检查 reactor.agent-browser.enabled 和 KERNEL_API_KEY");
        }
        return agentBrowserSessionPort;
    }
}
