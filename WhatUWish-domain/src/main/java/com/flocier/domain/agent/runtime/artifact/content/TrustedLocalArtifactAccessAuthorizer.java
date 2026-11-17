package com.flocier.domain.agent.runtime.artifact.content;

import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import org.springframework.stereotype.Component;

/** 当前单用户部署的最小授权实现；它不等价于未来多租户认证。 */
@Component
public class TrustedLocalArtifactAccessAuthorizer implements ArtifactAccessAuthorizer {
    @Override
    public boolean isAllowed(ArtifactAccessSubject subject, ArtifactLedgerEntity artifact, String runId) {
        return subject != null && subject.isTrustedLocal() && artifact != null && runId.equals(artifact.getRunId());
    }
}
