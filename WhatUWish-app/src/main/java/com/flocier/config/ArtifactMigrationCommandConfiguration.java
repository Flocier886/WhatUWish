package com.flocier.config;

import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationResult;
import com.flocier.domain.agent.runtime.artifact.migration.ArtifactMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 默认关闭的运维入口；不提供 HTTP API。 */
@Configuration
@ConditionalOnProperty(prefix = "artifact.migration", name = "enabled", havingValue = "true")
public class ArtifactMigrationCommandConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactMigrationCommandConfiguration.class);

    @Bean
    public ApplicationRunner artifactMigrationRunner(
            ArtifactMigrationService migrationService,
            @Value("${artifact.migration.action:dry-run}") String action,
            @Value("${artifact.migration.artifact-id:}") String artifactId,
            @Value("${artifact.migration.limit:1}") int limit) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments arguments) {
                List<ArtifactMigrationResult> results = execute(migrationService, action, artifactId, limit);
                for (ArtifactMigrationResult result : results) {
                    LOGGER.info("Artifact migration operation={} status={} artifactId={} sourceType={} targetType={} size={} ledgerUpdated={} orphanObject={} errorCode={}",
                            result.getOperation(), result.getStatus(), result.getArtifactId(),
                            result.getSourceStorageType(), result.getTargetStorageType(), result.getSize(),
                            result.isLedgerUpdated(), result.isOrphanObject(), result.getErrorCode());
                }
            }
        };
    }

    private List<ArtifactMigrationResult> execute(ArtifactMigrationService service, String action,
                                                  String artifactId, int limit) {
        String normalized = action == null ? "" : action.trim().toLowerCase();
        String id = artifactId == null ? "" : artifactId.trim();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        if ("dry-run".equals(normalized)) {
            return id.isEmpty() ? service.dryRunCandidates(safeLimit) : List.of(service.dryRun(id));
        }
        if ("migrate".equals(normalized)) {
            return id.isEmpty() ? service.migrateCandidates(safeLimit) : List.of(service.migrate(id));
        }
        if ("rollback".equals(normalized) && !id.isEmpty()) {
            return List.of(service.rollback(id));
        }
        throw new IllegalArgumentException("Artifact migration action 或 artifactId 配置无效");
    }
}
