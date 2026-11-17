package com.flocier.domain.agent.runtime.llm.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新 LLM 运行时根配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmSettings {

    /**
     * 默认模型别名。
     */
    private String defaultModel;

    /**
     * 已注册模型列表，key 为模型别名。
     */
    @Builder.Default
    private Map<String, LlmModelConfig> models = new LinkedHashMap<>();

    /**
     * provider 共享配置。
     */
    @Builder.Default
    private Map<String, LlmProviderConfig> providers = new LinkedHashMap<>();

    /**
     * 行为类配置。
     */
    @Builder.Default
    private LlmBehaviorConfig behavior = new LlmBehaviorConfig();
}
