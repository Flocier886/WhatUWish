package com.flocier.domain.ledger.model.replay;

/**
 * 单个 Run 回放时间线中的受控展示条目。
 *
 * <p>该模型是由 ledger 事实重建得到的领域展示投影，不是原始账本，也不属于 HTTP 协议。</p>
 */
public sealed interface ReplayTimelineEntry permits LlmReplayEntry, ToolReplayEntry,
        ArtifactReplayEntry, CompletedReplayEntry, FailedReplayEntry {

    ReplayEntryMetadata getMetadata();
}
