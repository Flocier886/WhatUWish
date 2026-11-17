package com.flocier.domain.ledger.service;

import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.ILlmInvocationLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IRunLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.ISessionLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IToolInvocationLedgerRepository;
import com.flocier.domain.ledger.model.entity.LedgerSessionQueryEntity;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerQueryService {

    /** Session 账本仓储。 */
    private final ISessionLedgerRepository sessionLedgerRepository;
    /** Run 主账本仓储。 */
    private final IRunLedgerRepository runLedgerRepository;
    /** LLM 调用账本仓储。 */
    private final ILlmInvocationLedgerRepository llmInvocationLedgerRepository;
    /** Tool 调用账本仓储。 */
    private final IToolInvocationLedgerRepository toolInvocationLedgerRepository;
    /** 产物账本仓储。 */
    private final IArtifactLedgerRepository artifactLedgerRepository;

    /** 按 runId 聚合查询一次完整执行视图。 */
    public Optional<LedgerRunView> queryRunView(String runId) {
        log.info("ledger query run view, runId={}", runId);
        Optional<LedgerRunView> result = runLedgerRepository.findByRunId(runId)
                .map(run -> LedgerRunView.builder()
                        .session(sessionLedgerRepository.findBySessionId(run.getSessionId()).orElse(null))
                        .run(run)
                        .llmInvocations(llmInvocationLedgerRepository.findByRunId(runId))
                        .toolInvocations(toolInvocationLedgerRepository.findByRunId(runId))
                        .artifacts(artifactLedgerRepository.findByRunId(runId))
                        .build());
        log.info("ledger query run view result, runId={}, found={}, llmCount={}, toolCount={}, artifactCount={}",
                runId,
                result.isPresent(),
                result.map(item -> item.getLlmInvocations().size()).orElse(0),
                result.map(item -> item.getToolInvocations().size()).orElse(0),
                result.map(item -> item.getArtifacts().size()).orElse(0));
        return result;
    }

    /** 按 sessionId、requestId、traceId 或 runId 查询执行主记录列表。 */
    public List<RunLedgerEntity> queryRuns(LedgerRunQueryEntity queryEntity) {
        log.info("ledger query runs, runId={}, sessionId={}, requestId={}, traceId={}, limit={}",
                queryEntity == null ? null : queryEntity.getRunId(),
                queryEntity == null ? null : queryEntity.getSessionId(),
                queryEntity == null ? null : queryEntity.getRequestId(),
                queryEntity == null ? null : queryEntity.getTraceId(),
                queryEntity == null ? null : queryEntity.getLimit());
        List<RunLedgerEntity> result;
        if (queryEntity == null) {
            result = runLedgerRepository.findBySessionId(null, 100);
        } else if (queryEntity.getRunId() != null) {
            result = runLedgerRepository.findByRunId(queryEntity.getRunId()).map(List::of).orElse(List.of());
        } else if (queryEntity.getRequestId() != null) {
            result = runLedgerRepository.findByRequestId(queryEntity.getRequestId()).map(List::of).orElse(List.of());
        } else if (queryEntity.getSessionId() != null) {
            result = runLedgerRepository.findBySessionId(queryEntity.getSessionId(), queryEntity.getLimit());
        } else if (queryEntity.getTraceId() != null) {
            result = runLedgerRepository.findByTraceId(queryEntity.getTraceId(), queryEntity.getLimit());
        } else {
            result = runLedgerRepository.findBySessionId(null, queryEntity.getLimit());
        }
        log.info("ledger query runs result, size={}", result.size());
        return result;
    }

    /** 按 sessionId 查询单个会话账本头信息。 */
    public Optional<SessionLedgerEntity> querySession(String sessionId) {
        log.info("ledger query session, sessionId={}", sessionId);
        return sessionLedgerRepository.findBySessionId(sessionId);
    }

    /** 查询最近活跃的 session 列表。 */
    public List<SessionLedgerEntity> queryRecentSessions(LedgerSessionQueryEntity queryEntity) {
        Integer limit = queryEntity == null ? null : queryEntity.getLimit();
        List<SessionLedgerEntity> result = sessionLedgerRepository.findRecentActiveSessions(limit);
        log.info("ledger query recent sessions result, size={}", result.size());
        return result;
    }

    /** 按工具调用账本 ID 查询产物。 */
    public List<ArtifactLedgerEntity> queryArtifactsByToolInvocationId(String toolInvocationId) {
        return artifactLedgerRepository.findByToolInvocationId(toolInvocationId);
    }

    /** 按 run 与 toolCallId 查询产物。 */
    public List<ArtifactLedgerEntity> queryArtifactsByRunIdAndToolCallId(String runId, String toolCallId) {
        return artifactLedgerRepository.findByRunIdAndToolCallId(runId, toolCallId);
    }

    /** 按 request 与 toolCallId 查询产物。 */
    public List<ArtifactLedgerEntity> queryArtifactsByRequestIdAndToolCallId(String requestId, String toolCallId) {
        return artifactLedgerRepository.findByRequestIdAndToolCallId(requestId, toolCallId);
    }
}
