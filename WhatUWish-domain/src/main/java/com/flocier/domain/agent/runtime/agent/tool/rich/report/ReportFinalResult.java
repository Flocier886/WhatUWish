package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** Report Python 终态读取模型；正文及 Artifact locator 均不得转入 Tool Result。 */
@Data
public class ReportFinalResult {
    private String schemaVersion;
    private String executionId;
    private String reportExecutionId;
    private String status;
    private String summary;
    private String outputType;
    private List<String> limitations = List.of();
    private Map<String, Integer> materialStatistics = new LinkedHashMap<>();
    private Map<String, Object> artifact;
    private String errorCode;
}
