package com.flocier.domain.agent.runtime.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具自带结果协议。
 * 由具体工具自己声明“要给主链路看的 observation”和“要给系统沉淀的结构化事实”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResultPayload {

    /**
     * 完整工具结果文本，可用于日志、调试和兼容展示。
     */
    private String toolResult;

    /**
     * 给 LLM 下一轮继续推理使用的 observation。
     */
    private String llmObservation;

    /**
     * 结构化输出，供系统逻辑、回放和调试使用。
     */
    private Object structuredOutput;

    /**
     * 适合落入 ledger 的结构化事实。
     */
    @Builder.Default
    private List<Map<String, Object>> ledgerFacts = new ArrayList<>();

    /**
     * 与本次工具调用相关的产物引用。
     */
    @Builder.Default
    private List<String> artifactRefs = new ArrayList<>();

    /**
     * 工具是否执行成功。
     */
    private boolean success;

    /**
     * 工具执行状态，常见值如 success、failed、partial_success。
     */
    private String status;

    /**
     * 失败时的错误码。
     */
    private String errorCode;

    /**
     * 失败时的错误信息。
     */
    private String errorMessage;

    /**
     * 额外元数据。
     */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /** 工具明确声明的 artifact 描述符，仅供运行时登记。 */
    @Builder.Default
    private List<Map<String, Object>> artifactDescriptors = new ArrayList<>();

    public static ToolResultPayload success(String toolResult,
                                            String llmObservation,
                                            Object structuredOutput) {
        return ToolResultPayload.builder()
                .success(true)
                .status("success")
                .toolResult(toolResult)
                .llmObservation(llmObservation)
                .structuredOutput(structuredOutput)
                .build();
    }

    public static ToolResultPayload failure(String errorCode,
                                            String errorMessage,
                                            String llmObservation) {
        return ToolResultPayload.builder()
                .success(false)
                .status("failed")
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .toolResult(errorMessage)
                .llmObservation(llmObservation)
                .build();
    }
}
