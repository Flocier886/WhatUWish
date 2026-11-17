package com.flocier.domain.agent.runtime.agent.react.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ReAct think 阶段输出的工具计划。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactToolPlan {

    private String toolName;

    @Builder.Default
    private Map<String, Object> arguments = new LinkedHashMap<>();

    private Integer sequence;

    /**
     * 预留并行组字段，本期仅串行执行。
     */
    private String parallelGroup;
}
