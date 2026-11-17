package com.flocier.domain.agent.runtime.llm.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新 LLM 运行时统一请求对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    /**
     * 模型别名或模型名；为空时使用默认模型。
     */
    private String model;

    @Builder.Default
    private List<LlmMessage> messages = new ArrayList<>();

    private Double temperature;

    private Integer maxTokens;

    /**
     * 预留流式调用位，本期不做完整流式事件实现。
     */
    @Builder.Default
    private Boolean stream = Boolean.FALSE;

    /**
     * 为后续账单、trace、tool、skill 扩展预留。
     */
    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
