package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import lombok.Data;

/** 仅用于当前 Live Run 的临时 Report 正文片段；不属于 Ledger 事实。 */
@Data
public class ReportContentDelta {
    private String schemaVersion;
    private String executionId;
    private String outputType;
    private Integer chunkIndex;
    private String content;
}
