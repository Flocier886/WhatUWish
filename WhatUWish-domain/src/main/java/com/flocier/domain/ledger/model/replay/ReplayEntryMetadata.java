package com.flocier.domain.ledger.model.replay;

import java.time.Instant;
import lombok.Getter;

/** 回放条目共有的稳定定位信息。 */
@Getter
public final class ReplayEntryMetadata {

    private final String eventId;
    private final int sequence;
    private final Instant occurredAt;
    private final String runId;
    private final boolean fallback;

    public ReplayEntryMetadata(String eventId, int sequence, Instant occurredAt, String runId, boolean fallback) {
        this.eventId = eventId;
        this.sequence = sequence;
        this.occurredAt = occurredAt;
        this.runId = runId;
        this.fallback = fallback;
    }
}
