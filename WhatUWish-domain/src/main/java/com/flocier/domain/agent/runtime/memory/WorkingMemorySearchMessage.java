package com.flocier.domain.agent.runtime.memory;

import lombok.Data;

/** 工作记忆历史检索行；压缩后原行 ID 仍可作为阅读锚点。 */
@Data
public class WorkingMemorySearchMessage {
    /** 持久化消息 ID，可直接用于 around_message_id 定位原始历史。 */
    private Long id;
    private String sessionId;
    private Long turnId;
    private String requestId;
    /** 压缩重投影前后的来源标识，用于发现结果去重。 */
    private String originMessageKey;
    /** 会话内轮次顺序；与 seqNo、id 共同确定消息的历史顺序。 */
    private Integer turnSeq;
    private Integer seqNo;
    private String role;
    private String content;
    private String messageKind;
    /** 所属轮次状态；INVALID 表示已退出活动上下文，但仍可作为旧记忆查询。 */
    private Integer turnStatus;
    /** FULLTEXT 相关度，仅全文检索结果可能带有该值。 */
    private Double searchScore;

    /** 压缩前后投影的兜底稳定标识。 */
    public String stableKey() {
        return sessionId + ":" + requestId + ":" + seqNo;
    }
}
