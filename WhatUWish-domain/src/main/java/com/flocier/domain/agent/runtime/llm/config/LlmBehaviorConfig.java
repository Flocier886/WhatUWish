package com.flocier.domain.agent.runtime.llm.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 运行时行为配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmBehaviorConfig {

    /**
     * 是否提取 usage 信息。
     */
    @Builder.Default
    private Boolean extractUsage = Boolean.TRUE;

    /**
     * 是否打印原始响应。
     */
    @Builder.Default
    private Boolean logRawResponse = Boolean.FALSE;

    /**
     * 是否启用回退策略预留位。
     */
    @Builder.Default
    private Boolean enableFallback = Boolean.FALSE;
}
