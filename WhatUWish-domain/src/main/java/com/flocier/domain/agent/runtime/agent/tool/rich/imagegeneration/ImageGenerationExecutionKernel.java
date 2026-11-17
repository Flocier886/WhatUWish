package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import java.util.function.Consumer;

/**
 * 图片生成两类入口共享的私有执行边界。
 *
 * <p>该内核只负责 Java/Python 流式协议、终态一致性和受控错误收口，不记录 Runtime 账本、
 * 不登记 Artifact，也不构造 LLM observation。Agent Tool 与图片工作台分别在入口侧完成这些职责。</p>
 */
public interface ImageGenerationExecutionKernel {

    ImageGenerationFinalResult execute(
            String pythonService,
            String pythonEndpoint,
            ImageGenerationStartRequest request,
            Consumer<ImageGenerationStreamEvent> eventConsumer
    );
}
