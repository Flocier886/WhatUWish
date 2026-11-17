package com.flocier.domain.agent.runtime.event;

import lombok.Getter;

/** 模型响应事件的受控展示数据。 */
@Getter
public final class LlmResponsePayload implements RuntimeEventPayload {

    private final String llmInvocationId;
    private final Integer stepNo;
    private final String model;
    private final String scene;
    private final String text;

    public LlmResponsePayload(String llmInvocationId, Integer stepNo, String model, String scene, String text) {
        this.llmInvocationId = llmInvocationId;
        this.stepNo = stepNo;
        this.model = model;
        this.scene = scene;
        this.text = text;
    }
}
