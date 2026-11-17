package com.flocier.types.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 外部 OpenCLI 程序的命令、固定 argv、执行预算和缓存位置；默认不启用。 */
@Data
@ConfigurationProperties(prefix = "reactor.opencli")
public class OpenCliProperties {
    private boolean enabled = false;
    private String command = "opencli";
    private List<String> prefixArgs = new ArrayList<>();
    private List<String> extraArgs = new ArrayList<>(List.of("-f", "json"));
    private long defaultTimeoutMs = 180_000L;
    private long maxTimeoutMs = 540_000L;
    private int maxOutputChars = 200_000;
    private String cacheDir = "";
    private String relayUrl = "";
}
