package com.flocier.domain.ledger.service.rich;

import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import lombok.Value;

/** 公共 Rich 过程事实记录结果。 */
@Value
public class RichRuntimeProgressResult {
    RuntimeEventLedgerEntity fact;
    boolean duplicate;

    public static RichRuntimeProgressResult duplicateResult() {
        return new RichRuntimeProgressResult(null, true);
    }
}
