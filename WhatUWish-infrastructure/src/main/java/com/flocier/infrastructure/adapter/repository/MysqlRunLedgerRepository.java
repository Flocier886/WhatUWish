package com.flocier.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.ledger.adapter.repository.IRunLedgerRepository;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerStats;
import com.flocier.infrastructure.dao.IRunLedgerDao;
import com.flocier.infrastructure.dao.po.RunLedgerPO;
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
public class MysqlRunLedgerRepository implements IRunLedgerRepository {

    @Resource
    private IRunLedgerDao runLedgerDao;

    @Override
    public void save(RunLedgerEntity entity) {
        runLedgerDao.insert(toPo(entity));
    }

    @Override
    public void update(RunLedgerEntity entity) {
        runLedgerDao.updateByRunId(toPo(entity));
    }

    @Override
    public Optional<RunLedgerEntity> findByRunId(String runId) {
        return Optional.ofNullable(runLedgerDao.queryByRunId(runId)).map(this::toEntity);
    }

    @Override
    public Optional<RunLedgerEntity> findByRequestId(String requestId) {
        return Optional.ofNullable(runLedgerDao.queryByRequestId(requestId)).map(this::toEntity);
    }

    @Override
    public Optional<RunLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(runLedgerDao.queryByIdempotencyKey(idempotencyKey)).map(this::toEntity);
    }

    @Override
    public List<RunLedgerEntity> findBySessionId(String sessionId, Integer limit) {
        return runLedgerDao.queryBySessionId(sessionId, limit).stream().map(this::toEntity).toList();
    }

    @Override
    public RunLedgerStats countBySessionId(String sessionId) {
        return RunLedgerStats.fromRuns(findBySessionId(sessionId, Integer.MAX_VALUE));
    }

    @Override
    public List<RunLedgerEntity> findByTraceId(String traceId, Integer limit) {
        return runLedgerDao.queryByTraceId(traceId, limit).stream().map(this::toEntity).toList();
    }

    private RunLedgerPO toPo(RunLedgerEntity entity) {
        return RunLedgerPO.builder()
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .requestId(entity.getRequestId())
                .traceId(entity.getTraceId())
                .agentId(entity.getAgentId())
                .tenantId(entity.getTenantId())
                .userId(entity.getUserId())
                .triggerSource(entity.getTriggerSource())
                .status(entity.getStatus())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .summary(entity.getSummary())
                .queryText(entity.getQueryText())
                .idempotencyKey(entity.getIdempotencyKey())
                .extJson(writeExt(entity.getExt()))
                .startedAt(toLocalDateTime(entity.getStartedAt()))
                .finishedAt(toLocalDateTime(entity.getFinishedAt()))
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .updateTime(toLocalDateTime(entity.getUpdatedAt()))
                .build();
    }

    private RunLedgerEntity toEntity(RunLedgerPO po) {
        return RunLedgerEntity.builder()
                .runId(po.getRunId())
                .sessionId(po.getSessionId())
                .requestId(po.getRequestId())
                .traceId(po.getTraceId())
                .agentId(po.getAgentId())
                .tenantId(po.getTenantId())
                .userId(po.getUserId())
                .triggerSource(po.getTriggerSource())
                .status(po.getStatus())
                .errorCode(po.getErrorCode())
                .errorMessage(po.getErrorMessage())
                .summary(po.getSummary())
                .queryText(po.getQueryText())
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
