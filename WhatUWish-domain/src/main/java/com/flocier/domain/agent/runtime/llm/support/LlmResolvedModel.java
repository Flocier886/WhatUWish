package com.flocier.domain.agent.runtime.llm.support;

import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 解析完成后的可调用模型配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResolvedModel {

    private String alias;

    private LlmModelConfig config;
}
