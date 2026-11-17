package com.flocier.domain.agent.runtime.memory;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;

import java.util.List;

/** 工作记忆存储端口；与保存运行事实的 Ledger 和保存产物的 Artifact 分开。 */
public interface SessionWorkingMemoryRepository {
    /** 只恢复 READY 轮次，排除当前请求，避免重试时把自身再次注入模型。 */
    List<LlmMessage> loadReadyMessages(String sessionId, String currentRequestId);

    /** 请求已有工作记忆段时拒绝重新执行，避免重复调用有副作用的工具。 */
    boolean hasRequest(String requestId);

    /** 当前增量段先落库再进入模型上下文；相同序号只允许完全相同的消息重试。 */
    void appendMessage(String sessionId, String segmentId, String runId, int seqNo, LlmMessage message);

    /** 仅在整条工作流正常完成后确认当前增量段。 */
    void completeSegment(String segmentId);

    /** 正常完成后按 requestId 幂等追加本轮主 ReAct 消息增量。 */
    boolean persistTurn(String sessionId, String requestId, String runId, List<LlmMessage> messages);

    /** 压缩时用完整新投影替换 READY 投影，保留旧轮次为 INVALID。 */
    boolean replaceReadyProjection(String sessionId, String compactRequestId, List<LlmMessage> messages);

    /** 记录压缩成功或失败的诊断事实，不作为模型工作记忆读取。 */
    void recordCompaction(WorkingMemoryCompactionEvent event);
}
