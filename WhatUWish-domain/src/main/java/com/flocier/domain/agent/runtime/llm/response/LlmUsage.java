package com.flocier.domain.agent.runtime.llm.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM token 用量对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsage {

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;
}
