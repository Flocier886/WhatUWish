package com.flocier.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.ledger.adapter.repository.ISessionLedgerRepository;
import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;
import com.flocier.infrastructure.dao.ISessionLedgerDao;
import com.flocier.infrastructure.dao.po.SessionLedgerPO;
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
public class MysqlSessionLedgerRepository implements ISessionLedgerRepository {

    @Resource
    private ISessionLedgerDao sessionLedgerDao;

    @Override
    public void save(SessionLedgerEntity entity) {
        sessionLedgerDao.insert(toPo(entity));
    }

    @Override
    public void update(SessionLedgerEntity entity) {
        sessionLedgerDao.updateBySessionId(toPo(entity));
    }

    @Override
    public Optional<SessionLedgerEntity> findBySessionId(String sessionId) {
        return Optional.ofNullable(sessionLedgerDao.queryBySessionId(sessionId)).map(this::toEntity);
    }

    @Override
    public Optional<SessionLedgerEntity> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(sessionLedgerDao.queryByIdempotencyKey(idempotencyKey)).map(this::toEntity);
    }

    @Override
    public List<SessionLedgerEntity> findRecentActiveSessions(Integer limit) {
        return sessionLedgerDao.queryRecentActiveSessions(limit).stream().map(this::toEntity).toList();
    }

    private SessionLedgerPO toPo(SessionLedgerEntity entity) {
        return SessionLedgerPO.builder()
                .sessionId(entity.getSessionId())
                .tenantId(entity.getTenantId())
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .title(entity.getTitle())
                .status(entity.getStatus())
                .latestRequestId(entity.getLatestRequestId())
                .latestQueryText(entity.getLatestQueryText())
                .latestSummaryText(entity.getLatestSummaryText())
                .runCount(entity.getRunCount())
                .finishedRunCount(entity.getFinishedRunCount())
                .failedRunCount(entity.getFailedRunCount())
                .idempotencyKey(entity.getIdempotencyKey())
                .extJson(writeExt(entity.getExt()))
                .startedAt(toLocalDateTime(entity.getStartedAt()))
                .lastActiveAt(toLocalDateTime(entity.getLastActiveAt()))
                .createTime(toLocalDateTime(entity.getCreatedAt()))
                .updateTime(toLocalDateTime(entity.getUpdatedAt()))
                .build();
    }

    private SessionLedgerEntity toEntity(SessionLedgerPO po) {
        return SessionLedgerEntity.builder()
                .sessionId(po.getSessionId())
                .tenantId(po.getTenantId())
                .userId(po.getUserId())
                .agentId(po.getAgentId())
                .title(po.getTitle())
                .status(po.getStatus())
                .latestRequestId(po.getLatestRequestId())
                .latestQueryText(po.getLatestQueryText())
                .latestSummaryText(po.getLatestSummaryText())
                .runCount(po.getRunCount())
                .finishedRunCount(po.getFinishedRunCount())
                .failedRunCount(po.getFailedRunCount())
                .idempotencyKey(po.getIdempotencyKey())
                .ext(readExt(po.getExtJson()))
                .startedAt(toInstant(po.getStartedAt()))
                .lastActiveAt(toInstant(po.getLastActiveAt()))
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
