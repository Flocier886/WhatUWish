package com.flocier.domain.agent.runtime.memory;

import lombok.Data;

/** 工作记忆的单条持久化消息；与 ReAct 使用的 LlmMessage 相互投影。 */
@Data
public class WorkingMemoryMessage {
    private Long id;
    private String sessionId;
    private String memoryScope = "main";
    private Long turnId;
    private String requestId;
    /** 原始消息来源键；压缩重投影时沿用，便于追溯压缩前后的同一消息。 */
    private String originMessageKey;
    private String runId;
    private Integer seqNo;
    private String role;
    private String content;
    /** 对齐 master 表结构的预留字段，当前 ReAct 消息不写入。 */
    private String reasoningContent;
    private String toolCallId;
    private String toolCallsJson;
    /** 对齐 master 表结构的预留字段，当前 ReAct 消息不写入。 */
    private String base64Image;
    private String messageKind;
    private String visibility = "ALL";
    private Integer tokenEstimate = 0;
}
