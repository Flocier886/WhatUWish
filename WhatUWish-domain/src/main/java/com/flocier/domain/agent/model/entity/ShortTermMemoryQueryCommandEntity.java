package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortTermMemoryQueryCommandEntity {
    /**
     * 会话ID。
     */
    private String sessionId;

    /**
     * 需要返回的最近轮次数量，不传则默认返回窗口内所有记录。
     */
    private Integer recentRounds;
}
