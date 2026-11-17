package com.flocier.domain.agent.runtime.llm.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新 LLM 运行时统一响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    private String model;

    private String provider;

    private String text;

    private String finishReason;

    private LlmUsage usage;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
