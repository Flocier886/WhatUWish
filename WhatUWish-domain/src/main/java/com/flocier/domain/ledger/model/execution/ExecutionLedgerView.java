package com.flocier.domain.ledger.model.execution;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** session 或多个 run 的批量结构化账本读取结果。 */
@Value
@Builder
public class ExecutionLedgerView {
    String sessionId;
    List<ExecutionRunView> runs;
}
