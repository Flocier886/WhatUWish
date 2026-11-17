package com.flocier.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.ledger.adapter.repository.ILlmInvocationLedgerRepository;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.infrastructure.dao.ILlmInvocationLedgerDao;
import com.flocier.infrastructure.dao.po.LlmInvocationLedgerPO;
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
public class MysqlLlmInvocationLedgerRepository implements ILlmInvocationLedgerRepository {

    @Resource
    private ILlmInvocationLedgerDao llmInvocationLedgerDao;

    @Override
    public void save(LlmInvocationLedgerEntity entity) {
        llmInvocationLedgerDao.insert(toPo(entity));
    }

    @Override
    public Optional<LlmInvocationLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(llmInvocationLedgerDao.queryByIdempotencyKey(idempotencyKey)).map(this::toEntity);
    }

    @Override
    public List<LlmInvocationLedgerEntity> findByRunId(String runId) {
        return llmInvocationLedgerDao.queryByRunId(runId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<LlmInvocationLedgerEntity> findByRunIds(List<String> runIds) {
        return runIds == null || runIds.isEmpty() ? List.of()
                : llmInvocationLedgerDao.queryByRunIds(runIds).stream().map(this::toEntity).toList();
    }

    @Override
    public List<LlmInvocationLedgerEntity> findByTraceId(String traceId, Integer limit) {
        return llmInvocationLedgerDao.queryByTraceId(traceId, limit).stream().map(this::toEntity).toList();
    }

    private LlmInvocationLedgerPO toPo(LlmInvocationLedgerEntity entity) {
        return LlmInvocationLedgerPO.builder()
                .llmInvocationId(entity.getLlmInvocationId())
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .requestId(entity.getRequestId())
                .traceId(entity.getTraceId())
                .stepNo(entity.getStepNo())
                .provider(entity.getProvider())
                .model(entity.getModel())
                .scene(entity.getScene())
                .promptTemplateId(entity.getPromptTemplateId())
                .requestSummary(entity.getRequestSummary())
                .responseSummary(entity.getResponseSummary())
                .requestPayloadJson(entity.getRequestPayloadJson())
                .responsePayloadJson(entity.getResponsePayloadJson())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .totalTokens(entity.getTotalTokens())
                .latencyMs(entity.getLatencyMs())
                .status(entity.getStatus())
                .artifactRef(entity.getArtifactRef())
                .idempotencyKey(entity.getIdempotencyKey())
                .extJson(writeExt(entity.getExt()))
                .startedAt(toLocalDateTime(entity.getStartedAt()))
                .finishedAt(toLocalDateTime(entity.getFinishedAt()))
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .updateTime(toLocalDateTime(entity.getUpdatedAt()))
                .build();
    }

    private LlmInvocationLedgerEntity toEntity(LlmInvocationLedgerPO po) {
        return LlmInvocationLedgerEntity.builder()
                .llmInvocationId(po.getLlmInvocationId())
                .runId(po.getRunId())
                .sessionId(po.getSessionId())
                .requestId(po.getRequestId())
                .traceId(po.getTraceId())
                .stepNo(po.getStepNo())
                .provider(po.getProvider())
                .model(po.getModel())
                .scene(po.getScene())
                .promptTemplateId(po.getPromptTemplateId())
                .requestSummary(po.getRequestSummary())
                .responseSummary(po.getResponseSummary())
                .requestPayloadJson(po.getRequestPayloadJson())
                .responsePayloadJson(po.getResponsePayloadJson())
                .inputTokens(po.getInputTokens())
                .outputTokens(po.getOutputTokens())
                .totalTokens(po.getTotalTokens())
                .latencyMs(po.getLatencyMs())
                .status(po.getStatus())
                .artifactRef(po.getArtifactRef())
                .idempotencyKey(po.getIdempotencyKey())
                .ext(readExt(po.getExtJson()))
                .startedAt(toInstant(po.getStartedAt()))
                .finishedAt(toInstant(po.getFinishedAt()))
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
