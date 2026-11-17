package com.flocier.application.agent.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessSubject;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Trigger 的文件访问编排入口；当前主体仅表示本地可信部署。 */
@Service
@RequiredArgsConstructor
public class ArtifactContentApplicationService {
    private final ArtifactContentAccessService artifactContentAccessService;

    public ArtifactDeliveryFile metadata(String runId, String artifactId) {
        return artifactContentAccessService.metadata(runId, artifactId, ArtifactAccessSubject.localTrustedSubject());
    }

    public ArtifactContentAccessService.ArtifactPreview preview(String runId, String artifactId) {
        return artifactContentAccessService.preview(runId, artifactId, ArtifactAccessSubject.localTrustedSubject());
    }

    public ArtifactContentAccessService.ArtifactDownload download(String runId, String artifactId) {
        return artifactContentAccessService.download(runId, artifactId, ArtifactAccessSubject.localTrustedSubject());
    }
}
