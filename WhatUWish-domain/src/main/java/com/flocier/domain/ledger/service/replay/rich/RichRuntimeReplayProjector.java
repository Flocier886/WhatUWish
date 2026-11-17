package com.flocier.domain.ledger.service.replay.rich;

import com.flocier.domain.ledger.model.entity.RunLedgerEntity;

/** Rich Tool 多阶段过程事实的回放扩展合同。 */
public interface RichRuntimeReplayProjector {
    RichRuntimeReplayProjection project(RunLedgerEntity run);
}
