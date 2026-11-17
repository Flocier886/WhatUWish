package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/** Python Report 稳定阶段事件；其 data 是内部接收模型，不能直接投影到前端。 */
@Data
public class ReportStreamEvent {
    private String streamEventType;
    private String schemaVersion;
    private String eventId;
    private String executionId;
    private Integer sequence;
    private String phase;
    private String status;
    private Instant occurredAt;
    private String summary;
    private Map<String, Object> data = new LinkedHashMap<>();
}
