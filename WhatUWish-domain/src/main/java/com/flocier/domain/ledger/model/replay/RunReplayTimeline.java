package com.flocier.domain.ledger.model.replay;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 一个 Run 的有序回放展示时间线，不包含或替代原始 ledger 事实。 */
@Value
@Builder
public class RunReplayTimeline {
    String sessionId;
    String runId;
    List<ReplayTimelineEntry> entries;
}
