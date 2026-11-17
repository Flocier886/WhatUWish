package com.flocier.types.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本机扩展 Relay 的路径、配对/RPC 时限、内部密钥和开发期固定 owner。 */
@Data
@ConfigurationProperties(prefix = "reactor.browser-relay")
public class BrowserRelayProperties {
    private boolean enabled = false;
    private String path = "/api/v1/browser/relay";
    private int pairingTtlSeconds = 300;
    private int rpcTimeoutSeconds = 60;
    private String internalRpcSecret = "";
    private String localOwnerId = "local-developer";
}
