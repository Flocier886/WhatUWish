package com.flocier.infrastructure.artifact;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore;
import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationService;
import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationWriter;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "artifact.content.s3", name = "enabled", havingValue = "true")
public class ArtifactMigrationRuntimeConfiguration {

    @Bean
    public ArtifactMigrationService artifactMigrationService(IArtifactLedgerRepository repository,
                                                             ArtifactContentStore contentStore,
                                                             ArtifactMigrationWriter writer) {
        return new ArtifactMigrationService(repository, contentStore, writer);
    }
}
