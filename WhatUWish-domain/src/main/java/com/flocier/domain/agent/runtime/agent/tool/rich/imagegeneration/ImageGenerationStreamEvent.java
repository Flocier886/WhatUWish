package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/** Python Image 稳定阶段事件的私有接收模型，data 绝不直接投影给浏览器。 */
@Data
public class ImageGenerationStreamEvent {
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
