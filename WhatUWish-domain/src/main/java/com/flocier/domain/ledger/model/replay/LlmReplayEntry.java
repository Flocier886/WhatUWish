package com.flocier.domain.ledger.model.replay;

import lombok.Getter;

/** 模型响应的回放展示条目。 */
@Getter
public final class LlmReplayEntry implements ReplayTimelineEntry {

    private final ReplayEntryMetadata metadata;
    private final String llmInvocationId;
    private final String model;
    private final String scene;
    private final String text;

    public LlmReplayEntry(ReplayEntryMetadata metadata, String llmInvocationId, String model, String scene, String text) {
        this.metadata = metadata;
        this.llmInvocationId = llmInvocationId;
        this.model = model;
        this.scene = scene;
        this.text = text;
    }
}
