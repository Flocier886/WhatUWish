package com.flocier.domain.ledger.model.execution;

import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 一个 run 的有序执行结构，未关联工具和产物均不得丢失。 */
@Value
@Builder
public class ExecutionRunView {
    RunLedgerEntity run;
    List<ExecutionLlmCycle> llmCycles;
    List<ExecutionToolInvocation> orphanToolInvocations;
    List<ArtifactLedgerEntity> unassociatedArtifacts;
}
