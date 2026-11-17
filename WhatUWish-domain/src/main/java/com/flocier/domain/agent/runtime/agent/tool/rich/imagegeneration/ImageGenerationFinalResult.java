package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** Python Image 终态读取模型；Artifact locator 仅用于同一私有传输后的 Java 登记。 */
@Data
public class ImageGenerationFinalResult {
    private String schemaVersion;
    private String executionId;
    private String imageExecutionId;
    private String status;
    private String summary;
    private String mode;
    private String size;
    private Integer requestedCount;
    private Boolean usedFallback;
    private List<Map<String, Object>> artifacts = new ArrayList<>();
    private String errorCode;
}
