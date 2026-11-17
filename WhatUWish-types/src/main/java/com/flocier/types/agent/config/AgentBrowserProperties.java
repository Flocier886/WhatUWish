package com.flocier.types.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可选 Kernel 远端浏览器配置；API Key 只由后端读取。 */
@Data
@ConfigurationProperties(prefix = "reactor.agent-browser")
public class AgentBrowserProperties {
    private boolean enabled = false;
    private String apiKey = "";
    private String baseUrl = "https://api.onkernel.com";
    private int timeoutSeconds = 259200;
    private String browserNamePrefix = "rb";
    private String profileName = "agent";
    private boolean profileSaveChanges = false;
}
