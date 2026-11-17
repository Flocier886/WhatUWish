package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortTermMemoryQueryResultEntity {
    /**
     * 会话ID。
     */
    private String sessionId;

    /**
     * 截止当前累积轮次数（包含已摘要历史）。
     */
    private Long totalRounds;

    /**
     * 历史摘要（窗口外内容）。
     */
    private String summary;

    /**
     * 窗口内短期记忆。
     */
    private List<ShortTermMemoryRecordEntity> recentRecords;
}
