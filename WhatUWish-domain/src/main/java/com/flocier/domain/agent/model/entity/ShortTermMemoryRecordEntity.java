package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortTermMemoryRecordEntity {
    /**
     * 对话轮次，从1开始递增。
     */
    private Long round;

    /**
     * 用户提问。
     */
    private String userQuestion;

    /**
     * 最终回答。
     */
    private String finalAnswer;

    /**
     * 写入时间戳。
     */
    private Long timestamp;
}
