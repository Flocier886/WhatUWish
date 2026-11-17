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
 * Skill 执行结果。
 * Skill 本质上也是一种特殊工具，因此同样需要显式返回 observation、结构化输出和 ledger 事实。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionResult {

    /**
     * 执行是否成功。
     */
    private boolean success;

    /**
     * Skill 最终回答。
     */
    private String finalAnswer;

    /**
     * 供主链路继续推理的 observation。
     */
    private String llmObservation;

    /**
     * Skill 执行摘要。
     */
    private String summary;

    /**
     * Skill 结构化输出。
     */
    @Builder.Default
    private Map<String, Object> structuredOutput = new LinkedHashMap<>();

    /**
     * 供 ledger 落库的结构化事实。
     */
    @Builder.Default
    private List<Map<String, Object>> ledgerFacts = new ArrayList<>();

    /**
     * Skill 产生的产物引用。
     */
    @Builder.Default
    private List<String> artifactRefs = new ArrayList<>();

    /**
     * Skill 内部使用过的工具列表。
     */
    @Builder.Default
    private List<String> usedTools = new ArrayList<>();

    /**
     * 执行状态。
     */
    private String status;

    /**
     * 错误码。
     */
    private String errorCode;

    /**
     * 错误信息。
     */
    private String errorMessage;

    /**
     * 本次 skill execution 标识。
     */
    private String skillExecutionId;

    /**
     * skill 名称。
     */
    private String skillName;

    /**
     * 兼容旧账本摘要字段。
     */
    private String skillResultSummary;
}
