package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import lombok.Builder;
import lombok.Value;

/** Rich Tool 运行中登记正式 Artifact 所需的最小上下文。 */
@Value
@Builder
public class ArtifactRuntimeRegistrationRequest {
    AgentContext context;
    ToolInvocationLedgerEntity toolInvocation;
    ArtifactRuntimeSource source;
    ArtifactRuntimeDescriptor descriptor;
}
