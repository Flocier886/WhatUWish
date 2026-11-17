package com.flocier.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.infrastructure.dao.IArtifactLedgerDao;
import com.flocier.infrastructure.dao.po.ArtifactLedgerPO;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "ledger.persistence", name = "type", havingValue = "mysql")
public class MysqlArtifactLedgerRepository implements IArtifactLedgerRepository {

    @Resource
    private IArtifactLedgerDao artifactLedgerDao;

    @Override
    public void save(ArtifactLedgerEntity entity) {
        artifactLedgerDao.insert(toPo(entity));
    }

    @Override
    public Optional<ArtifactLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(artifactLedgerDao.queryByIdempotencyKey(idempotencyKey)).map(this::toEntity);
    }

    @Override
    public Optional<ArtifactLedgerEntity> findByArtifactId(String artifactId) {
        return Optional.ofNullable(artifactLedgerDao.queryByArtifactId(artifactId)).map(this::toEntity);
    }

    @Override
    public List<ArtifactLedgerEntity> findMigrationCandidates(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return artifactLedgerDao.queryMigrationCandidates(safeLimit).stream().map(this::toEntity).toList();
    }

    @Override
    public boolean updateStorageLocator(String artifactId, String expectedStorageType, String expectedStorageKey,
                                        String newStorageType, String newStorageKey, String newStorageRef) {
        return artifactLedgerDao.updateStorageLocator(artifactId, expectedStorageType, expectedStorageKey,
                newStorageType, newStorageKey, newStorageRef) == 1;
    }

    @Override
    public List<ArtifactLedgerEntity> findByRunId(String runId) {
        return artifactLedgerDao.queryByRunId(runId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ArtifactLedgerEntity> findByRunIds(List<String> runIds) {
        return runIds == null || runIds.isEmpty() ? List.of()
                : artifactLedgerDao.queryByRunIds(runIds).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ArtifactLedgerEntity> findByRunIdsIncludingDeleted(List<String> runIds) {
        return runIds == null || runIds.isEmpty() ? List.of()
                : artifactLedgerDao.queryByRunIdsIncludingDeleted(runIds).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ArtifactLedgerEntity> findByTraceId(String traceId, Integer limit) {
        return artifactLedgerDao.queryByTraceId(traceId, limit).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ArtifactLedgerEntity> findByToolInvocationId(String toolInvocationId) {
        return artifactLedgerDao.queryByToolInvocationId(toolInvocationId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ArtifactLedgerEntity> findByRunIdAndToolCallId(String runId, String toolCallId) {
        return artifactLedgerDao.queryByRunIdAndToolCallId(runId, toolCallId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ArtifactLedgerEntity> findByRequestIdAndToolCallId(String requestId, String toolCallId) {
        return artifactLedgerDao.queryByRequestIdAndToolCallId(requestId, toolCallId).stream().map(this::toEntity).toList();
    }

    private ArtifactLedgerPO toPo(ArtifactLedgerEntity entity) {
        Long fileSize = entity.getFileSize() == null ? entity.getSize() : entity.getFileSize();
        return ArtifactLedgerPO.builder()
                .artifactId(entity.getArtifactId())
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .requestId(entity.getRequestId())
                .traceId(entity.getTraceId())
                .toolInvocationId(entity.getToolInvocationId())
                .toolCallId(entity.getToolCallId())
                .artifactRole(entity.getArtifactRole())
                .visibility(entity.getVisibility())
                .sourceType(entity.getSourceType())
                .sourceId(entity.getSourceId())
                .sourceName(entity.getSourceName())
                .artifactType(entity.getArtifactType())
                .artifactName(entity.getArtifactName())
                .fileName(entity.getFileName())
                .storageType(entity.getStorageType())
                .storageRef(entity.getStorageRef())
                .storageKey(entity.getStorageKey())
                .downloadUrl(entity.getDownloadUrl())
                .previewUrl(entity.getPreviewUrl())
                .contentSummary(entity.getContentSummary())
                .mimeType(entity.getMimeType())
                .size(entity.getSize() == null ? fileSize : entity.getSize())
                .fileSize(fileSize)
                .fileHash(entity.getFileHash())
                .metadataJson(entity.getMetadataJson())
                .deleted(entity.getDeleted() == null ? 0 : entity.getDeleted())
                .deletedAt(toLocalDateTime(entity.getDeletedAt()))
                .idempotencyKey(entity.getIdempotencyKey())
                .extJson(writeExt(entity.getExt()))
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .updateTime(toLocalDateTime(entity.getUpdatedAt()))
                .build();
    }

    private ArtifactLedgerEntity toEntity(ArtifactLedgerPO po) {
        return ArtifactLedgerEntity.builder()
                .artifactId(po.getArtifactId())
                .runId(po.getRunId())
                .sessionId(po.getSessionId())
                .requestId(po.getRequestId())
                .traceId(po.getTraceId())
                .toolInvocationId(po.getToolInvocationId())
                .toolCallId(po.getToolCallId())
                .artifactRole(po.getArtifactRole())
                .visibility(po.getVisibility())
                .sourceType(po.getSourceType())
                .sourceId(po.getSourceId())
                .sourceName(po.getSourceName())
                .artifactType(po.getArtifactType())
                .artifactName(po.getArtifactName())
                .fileName(po.getFileName())
                .storageType(po.getStorageType())
                .storageRef(po.getStorageRef())
                .storageKey(po.getStorageKey())
                .downloadUrl(po.getDownloadUrl())
                .previewUrl(po.getPreviewUrl())
                .contentSummary(po.getContentSummary())
                .mimeType(po.getMimeType())
                .size(po.getSize())
                .fileSize(po.getFileSize())
                .fileHash(po.getFileHash())
                .metadataJson(po.getMetadataJson())
                .deleted(po.getDeleted())
                .deletedAt(toInstant(po.getDeletedAt()))
                .idempotencyKey(po.getIdempotencyKey())
                .ext(readExt(po.getExtJson()))
                .createdAt(toInstant(po.getCreateTime()))
                .updatedAt(toInstant(po.getUpdateTime()))
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private String writeExt(Map<String, Object> ext) {
        return ext == null || ext.isEmpty() ? null : JSON.toJSONString(ext);
    }

    private Map<String, Object> readExt(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        return JSON.parseObject(extJson, new TypeReference<Map<String, Object>>() {});
    }
}
