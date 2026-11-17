package com.flocier.domain.ledger.model.execution;

import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 已绑定真实 artifact 的工具调用执行视图。 */
@Value
@Builder
public class ExecutionToolInvocation {
    ToolInvocationLedgerEntity toolInvocation;
    ToolLlmAssociation association;
    String parentLlmInvocationId;
    List<ArtifactLedgerEntity> artifacts;
}
