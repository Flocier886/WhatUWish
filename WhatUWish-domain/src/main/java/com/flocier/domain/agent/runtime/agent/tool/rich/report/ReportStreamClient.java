package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import java.util.function.Consumer;

/** 独立 Python Report SSE API 的 Java 客户端契约。 */
public interface ReportStreamClient {
    ReportFinalResult stream(String pythonService, String endpoint, ReportStartRequest request,
                             Consumer<ReportStreamEvent> eventConsumer,
                             Consumer<ReportContentDelta> deltaConsumer);
}
