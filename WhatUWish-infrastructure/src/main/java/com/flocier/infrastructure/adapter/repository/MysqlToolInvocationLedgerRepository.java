package com.flocier.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.ledger.adapter.repository.IToolInvocationLedgerRepository;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.infrastructure.dao.IToolInvocationLedgerDao;
import com.flocier.infrastructure.dao.po.ToolInvocationLedgerPO;
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
public class MysqlToolInvocationLedgerRepository implements IToolInvocationLedgerRepository {

    @Resource
    private IToolInvocationLedgerDao toolInvocationLedgerDao;

    @Override
    public void save(ToolInvocationLedgerEntity entity) {
        toolInvocationLedgerDao.insert(toPo(entity));
    }

    @Override
    public void update(ToolInvocationLedgerEntity entity) {
        if (entity == null || toolInvocationLedgerDao.update(toPo(entity)) != 1) {
            throw new IllegalStateException("Tool invocation does not exist: "
                    + (entity == null ? null : entity.getToolInvocationId()));
        }
    }

    @Override
    public Optional<ToolInvocationLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(toolInvocationLedgerDao.queryByIdempotencyKey(idempotencyKey)).map(this::toEntity);
    }

    @Override
    public List<ToolInvocationLedgerEntity> findByRunId(String runId) {
        return toolInvocationLedgerDao.queryByRunId(runId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ToolInvocationLedgerEntity> findByRunIds(List<String> runIds) {
        return runIds == null || runIds.isEmpty() ? List.of()
                : toolInvocationLedgerDao.queryByRunIds(runIds).stream().map(this::toEntity).toList();
    }

    @Override
    public List<ToolInvocationLedgerEntity> findByTraceId(String traceId, Integer limit) {
        return toolInvocationLedgerDao.queryByTraceId(traceId, limit).stream().map(this::toEntity).toList();
    }

    private ToolInvocationLedgerPO toPo(ToolInvocationLedgerEntity entity) {
        return ToolInvocationLedgerPO.builder()
                .toolInvocationId(entity.getToolInvocationId())
                .toolCallId(entity.getToolCallId())
                .llmInvocationId(entity.getLlmInvocationId())
                .dispatchOrder(entity.getDispatchOrder())
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .requestId(entity.getRequestId())
                .traceId(entity.getTraceId())
                .stepNo(entity.getStepNo())
                .toolName(entity.getToolName())
                .toolType(entity.getToolType())
                .toolVersion(entity.getToolVersion())
                .requestSummary(entity.getRequestSummary())
                .responseSummary(entity.getResponseSummary())
                .requestPayloadJson(entity.getRequestPayloadJson())
                .responsePayloadJson(entity.getResponsePayloadJson())
                .latencyMs(entity.getLatencyMs())
                .status(entity.getStatus())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .artifactRef(entity.getArtifactRef())
                .idempotencyKey(entity.getIdempotencyKey())
                .extJson(writeExt(entity.getExt()))
                .startedAt(toLocalDateTime(entity.getStartedAt()))
                .finishedAt(toLocalDateTime(entity.getFinishedAt()))
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .updateTime(toLocalDateTime(entity.getUpdatedAt()))
                .build();
    }

    private ToolInvocationLedgerEntity toEntity(ToolInvocationLedgerPO po) {
        return ToolInvocationLedgerEntity.builder()
                .toolInvocationId(po.getToolInvocationId())
                .toolCallId(po.getToolCallId())
                .llmInvocationId(po.getLlmInvocationId())
                .dispatchOrder(po.getDispatchOrder())
                .runId(po.getRunId())
                .sessionId(po.getSessionId())
                .requestId(po.getRequestId())
                .traceId(po.getTraceId())
                .stepNo(po.getStepNo())
                .toolName(po.getToolName())
                .toolType(po.getToolType())
                .toolVersion(po.getToolVersion())
                .requestSummary(po.getRequestSummary())
                .responseSummary(po.getResponseSummary())
                .requestPayloadJson(po.getRequestPayloadJson())
                .responsePayloadJson(po.getResponsePayloadJson())
                .latencyMs(po.getLatencyMs())
                .status(po.getStatus())
                .errorCode(po.getErrorCode())
                .errorMessage(po.getErrorMessage())
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
