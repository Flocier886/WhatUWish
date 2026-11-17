package com.flocier.domain.agent.runtime.event;

import org.springframework.util.StringUtils;

/**
 * Report 正文的非耐久 Live 增量。
 *
 * <p>它只用于当前 SSE 会话的临时草稿，不可写入 Runtime Event Ledger、Tool Invocation、
 * Report Execution 或 History Replay。完整正文仍以最终 Artifact 为唯一交付载体。</p>
 */
public final class ReportContentDeltaPayload implements RuntimeEventPayload {

    public static final String SCHEMA_VERSION = "report.content_delta.v1";
    public static final int MAX_CONTENT_LENGTH = 4000;

    private final String schemaVersion;
    private final String toolInvocationId;
    private final String toolName;
    private final String outputType;
    private final Integer chunkIndex;
    private final String content;

    public ReportContentDeltaPayload(
            String toolInvocationId,
            String toolName,
            String outputType,
            Integer chunkIndex,
            String content
    ) {
        if (!StringUtils.hasText(toolInvocationId)
                || !StringUtils.hasText(toolName)
                || !StringUtils.hasText(outputType)
                || !StringUtils.hasText(content)
                || chunkIndex == null || chunkIndex < 1 || content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("Report 内容增量不符合受控 Live 协议");
        }
        this.schemaVersion = SCHEMA_VERSION;
        this.toolInvocationId = toolInvocationId;
        this.toolName = toolName;
        this.outputType = outputType;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getToolInvocationId() {
        return toolInvocationId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getOutputType() {
        return outputType;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }
}
