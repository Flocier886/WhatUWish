package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import lombok.Value;

/** Artifact 运行中登记的持久化结果，不向前端暴露内部存储字段。 */
@Value
public class ArtifactRuntimeRegistrationResult {
    ArtifactLedgerEntity artifact;
    boolean created;
}
