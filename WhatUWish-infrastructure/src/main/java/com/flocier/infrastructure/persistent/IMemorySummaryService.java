package com.flocier.infrastructure.persistent;

import com.flocier.domain.agent.model.entity.ShortTermMemoryRecordEntity;

import java.util.List;

public interface IMemorySummaryService {
    String summarize(String existedSummary, List<ShortTermMemoryRecordEntity> overflowRecords);
}
