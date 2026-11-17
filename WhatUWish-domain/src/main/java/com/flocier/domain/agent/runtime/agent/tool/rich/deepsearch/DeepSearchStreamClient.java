package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import java.util.function.Consumer;

/** 独立 Python DeepSearch SSE API 的 Java 客户端契约。 */
public interface DeepSearchStreamClient {

    DeepSearchFinalResult stream(String pythonService,
                                 String endpoint,
                                 DeepSearchStartRequest request,
                                 Consumer<DeepSearchStreamEvent> eventConsumer);
}
