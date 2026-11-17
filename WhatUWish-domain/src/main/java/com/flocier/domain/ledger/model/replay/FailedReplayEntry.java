package com.flocier.domain.ledger.model.replay;

import lombok.Getter;

/** Run 失败时的唯一回放终态。 */
@Getter
public final class FailedReplayEntry implements ReplayTimelineEntry {

    private final ReplayEntryMetadata metadata;
    private final String errorCode;
    private final String errorMessage;

    public FailedReplayEntry(ReplayEntryMetadata metadata, String errorCode, String errorMessage) {
        this.metadata = metadata;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}
