package com.flocier.domain.agent.runtime.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {

    private String toolName;

    private String toolCallId;

    private String sourceType;

    private boolean success;

    private String status;

    private String errorCode;

    private String errorMessage;

    @Builder.Default
    private Map<String, Object> arguments = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /**
     * 工具完整结果文本，保留给日志与兼容层展示。
     */
    private String outputText;

    /**
     * 工具面向 LLM 的 observation。
     */
    private String llmObservation;

    /**
     * 工具结构化输出，供回放、调试和后续系统逻辑复用。
     */
    private Object structuredOutput;

    /**
     * 工具沉淀给 ledger 的结构化事实。
     */
    @Builder.Default
    private List<Map<String, Object>> ledgerFacts = List.of();


    private Instant startedAt;

    private Instant finishedAt;

    private Long durationMs;

    @Builder.Default
    private List<String> artifactRefs = List.of();

    /** 工具明确声明的 artifact 描述符，仅供运行时登记，不写入通用结果快照。 */
    @Builder.Default
    private List<Map<String, Object>> artifactDescriptors = List.of();

    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();

    public String getObservationSummary() {
        return llmObservation;
    }

    public String getPreferredObservationText() {
        if (llmObservation != null && !llmObservation.isBlank()) {
            return llmObservation;
        }
        if (outputText != null && !outputText.isBlank()) {
            return outputText;
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        return "";
    }

    public void putAttribute(String key, Object value) {
        if (attributes == null || key == null || value == null) {
            return;
        }
        attributes.put(key, value);
    }

    public boolean attributeEquals(String key, Object value) {
        if (attributes == null || key == null) {
            return false;
        }
        return Objects.equals(attributes.get(key), value);
    }
}
