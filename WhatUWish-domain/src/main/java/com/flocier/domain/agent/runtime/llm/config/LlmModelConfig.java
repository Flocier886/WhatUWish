package com.flocier.domain.agent.runtime.llm.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个可用模型配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmModelConfig {

    /**
     * provider 标识，便于后续做多 provider 合并。
     */
    private String provider;

    /**
     * 具体模型名。
     */
    private String model;

    /**
     * OpenAI-compatible baseUrl。
     */
    private String baseUrl;

    /**
     * 调用密钥。
     */
    private String apiKey;

    /**
     * chat completions 接口路径。
     */
    private String interfacePath;

    /**
     * 默认温度。
     */
    private Double temperature;

    /**
     * 默认最大输出 token。
     */
    private Integer maxTokens;

    /**
     * 预留的超时配置。
     */
    private Integer timeoutSeconds;

    /**
     * 额外透传参数。
     */
    @Builder.Default
    private Map<String, Object> extParams = new LinkedHashMap<>();
}
