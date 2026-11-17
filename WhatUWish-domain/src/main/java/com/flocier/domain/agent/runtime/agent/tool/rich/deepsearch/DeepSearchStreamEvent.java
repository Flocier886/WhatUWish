package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Python DeepSearch 发送的单条非最终 SSE 领域事件。 */
@Data
@NoArgsConstructor
public class DeepSearchStreamEvent {

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
    private Map<String, Object> presentation = new LinkedHashMap<>();
}
