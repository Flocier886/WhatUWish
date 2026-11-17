package com.flocier.domain.agent.runtime.artifact.content;

import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;

/** 不依赖 HTTP 的 Artifact 访问授权 Port。 */
public interface ArtifactAccessAuthorizer {
    boolean isAllowed(ArtifactAccessSubject subject, ArtifactLedgerEntity artifact, String runId);
}
