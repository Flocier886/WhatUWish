package com.flocier.domain.agent.browser;

import com.flocier.types.agent.config.BrowserRelayProperties;
import org.springframework.stereotype.Component;

/** 本地单用户开发模式的稳定浏览器归属；正式账号阶段替换此入口。 */
@Component
public class LocalBrowserOwner {
    private final BrowserRelayProperties properties;

    public LocalBrowserOwner(BrowserRelayProperties properties) {
        this.properties = properties;
    }

    public String requireOwnerId() {
        String owner = properties.getLocalOwnerId();
        if (owner == null || owner.isBlank()) throw new IllegalStateException("本地浏览器 owner 未配置");
        return owner.trim();
    }
}
