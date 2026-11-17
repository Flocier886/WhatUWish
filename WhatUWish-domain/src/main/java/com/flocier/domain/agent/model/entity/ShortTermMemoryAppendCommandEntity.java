package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortTermMemoryAppendCommandEntity {
    /**
     * 会话ID，用于分桶存储短期记忆。
     */
    private String sessionId;

    /**
     * 用户本轮提问。
     */
    private String userQuestion;

    /**
     * 工作流执行完成后的最终结果。
     */
    private String finalAnswer;

    /**
     * 可选窗口大小，不传则由服务端使用默认值。
     */
    private Integer windowSize;
}
