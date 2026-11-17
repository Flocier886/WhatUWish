package com.flocier.application.agent.imagegeneration.workbench;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 工作台图片生成历史的稳定分页结果。 */
@Value
@Builder
public class WorkbenchImageGenerationHistoryPage {
    String sessionId;
    int pageNo;
    int pageSize;
    long total;
    boolean hasMore;
    List<WorkbenchImageGenerationHistoryItem> items;
}
