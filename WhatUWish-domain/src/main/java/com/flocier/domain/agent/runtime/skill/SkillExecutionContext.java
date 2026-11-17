package com.flocier.domain.agent.runtime.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 skill 执行上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionContext {

    /** 本次 skill execution 唯一标识。 */
    private String skillExecutionId;
    /** skillId。 */
    private String skillId;
    /** skill 名称。 */
    private String skillName;
    /** 会话标识。 */
    private String sessionId;
    /** 本轮 run 标识。 */
    private String runId;
    /** 请求标识。 */
    private String requestId;
    /** trace 标识。 */
    private String traceId;
    /** 父工具调用标识。 */
    private String parentToolCallId;
    /** 技能执行任务文本。 */
    private String task;

    /** 结构化输入参数。 */
    @Builder.Default
    private Map<String, Object> input = new LinkedHashMap<>();

    /** 实际使用过的工具名。 */
    @Builder.Default
    private List<String> usedTools = new ArrayList<>();

    /** 中间结果。 */
    @Builder.Default
    private List<String> intermediateResults = new ArrayList<>();

    /** 生成的产物引用。 */
    @Builder.Default
    private List<String> generatedArtifacts = new ArrayList<>();

    /** 状态。 */
    private String state;
}
