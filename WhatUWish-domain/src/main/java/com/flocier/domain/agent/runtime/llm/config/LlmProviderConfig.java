package com.flocier.domain.agent.runtime.llm.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * provider 级别共享配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderConfig {

    private String baseUrl;

    private String apiKey;

    private String interfacePath;

    @Builder.Default
    private Map<String, Object> extParams = new LinkedHashMap<>();
}
