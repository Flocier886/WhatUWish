package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import java.util.function.Consumer;

/** Image 私有 SSE 传输端口；不依赖 Ledger、Artifact Runtime 或 UI。 */
public interface ImageGenerationStreamClient {
    ImageGenerationFinalResult stream(String pythonService, String endpoint, ImageGenerationStartRequest request,
                                      Consumer<ImageGenerationStreamEvent> eventConsumer);
}
