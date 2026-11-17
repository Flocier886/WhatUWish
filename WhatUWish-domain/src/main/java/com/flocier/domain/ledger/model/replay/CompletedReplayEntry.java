package com.flocier.domain.ledger.model.replay;

import lombok.Getter;

/** Run 正常完成时的唯一回放终态。 */
@Getter
public final class CompletedReplayEntry implements ReplayTimelineEntry {

    private final ReplayEntryMetadata metadata;
    private final String summary;
    private final String artifactSelection;

    public CompletedReplayEntry(ReplayEntryMetadata metadata, String summary, String artifactSelection) {
        this.metadata = metadata;
        this.summary = summary;
        this.artifactSelection = artifactSelection;
    }
}
