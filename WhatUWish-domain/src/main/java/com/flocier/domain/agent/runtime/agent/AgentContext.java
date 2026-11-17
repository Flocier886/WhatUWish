package com.flocier.domain.agent.runtime.agent;

import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistry;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.printer.Printer;
import com.flocier.domain.agent.runtime.stream.AgentResultWriter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {
    /** 单次运行模式由服务端设置；普通调用保持原有主链行为。 */
    @Builder.Default
    private AgentRunMode runMode = AgentRunMode.MAIN;
    /** 仅后台复盘持有的读取版本、文件变更与取消状态。 */
    private com.flocier.domain.agent.runtime.memory.review.SkillReviewState skillReviewState;
    /** 当前 ReAct 决策已落账的父 LLM invocation 标识。 */
    public static final String CURRENT_LLM_INVOCATION_ID_ATTRIBUTE = "currentLlmInvocationId";

    private String requestId;
    private String sessionId;
    private String traceId;
    private String query;
    /**
     * 当前执行任务原文。
     * 这里只保留任务本身，不再混入记忆注入文本。
     */
    private String task;
    /**
     * 记忆注入文本。
     * 用于在主链路中单独承载从账本或短期记忆中提取出的上下文提示。
     */
    private String memoryPrompt;
    /** 已持久化工作记忆或冷启动 Ledger 过渡消息。 */
    @Builder.Default
    private List<LlmMessage> workingMemoryMessages = new CopyOnWriteArrayList<>();
    /** 最近一次运行中压缩后的前缀长度，收口时只追加其后缀。 */
    private Integer workingMemoryPreloadSize;
    /** 进入 SummaryAgent 前冻结的主 ReAct 消息；后置摘要不写入工作记忆。 */
    private List<LlmMessage> reactMemorySnapshot;
    private Integer maxStep;
    private Integer currentStep;
    private Boolean completed;
    private String finishReason;
    private String result;
    private String lastThought;
    private String lastObservation;
    private ToolCollection toolCollection;
    private Printer printer;
    private AgentResultWriter resultWriter;
    @Builder.Default
    private List<LlmMessage> memory = new CopyOnWriteArrayList<>();
    @Builder.Default
    private List<ToolCall> lastToolCalls = new CopyOnWriteArrayList<>();
    @Builder.Default
    private List<ToolExecutionResult> toolExecutionResults = new CopyOnWriteArrayList<>();
    @Builder.Default
    private Map<String, Object> attributes = new ConcurrentHashMap<>();
    @Builder.Default
    private ArtifactRuntimeRegistry artifactRuntimeRegistry = new ArtifactRuntimeRegistry();

    /** 工作区结束时用 null 删除属性；ConcurrentHashMap 不允许保存 null 值。 */
    public <T> void setAttribute(String key, T value) {
        if (value == null) attributes.remove(key);
        else attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public void appendMemory(LlmMessage message) {
        if (message != null) {
            memory.add(message);
        }
    }

    public void replaceLastToolCalls(List<ToolCall> toolCalls) {
        lastToolCalls.clear();
        if (toolCalls != null) {
            lastToolCalls.addAll(toolCalls);
        }
    }

    public void appendToolExecutionResult(ToolExecutionResult result) {
        if (result != null) {
            toolExecutionResults.add(result);
        }
    }

    public ArtifactRuntimeRegistry requireArtifactRuntimeRegistry() {
        if (artifactRuntimeRegistry == null) {
            artifactRuntimeRegistry = new ArtifactRuntimeRegistry();
        }
        return artifactRuntimeRegistry;
    }

    /**
     * 当前工具调用所属的父 LLM invocation。
     * 仅由 LLM 执行器在成功完成账本写入后设置；没有父调用的兼容入口保持为空。
     */
    public void setCurrentLlmInvocationId(String llmInvocationId) {
        if (llmInvocationId == null || llmInvocationId.isBlank()) {
            attributes.remove(CURRENT_LLM_INVOCATION_ID_ATTRIBUTE);
            return;
        }
        attributes.put(CURRENT_LLM_INVOCATION_ID_ATTRIBUTE, llmInvocationId);
    }

    public String getCurrentLlmInvocationId() {
        Object value = attributes.get(CURRENT_LLM_INVOCATION_ID_ATTRIBUTE);
        return value == null ? null : String.valueOf(value);
    }
}
