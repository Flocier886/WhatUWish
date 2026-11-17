package com.flocier.domain.ledger.service;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.AgentRunMode;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LedgerFactEntity;
import com.flocier.domain.ledger.model.entity.LedgerFactQueryEntity;
import com.flocier.domain.ledger.model.entity.LedgerSessionQueryEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.model.vo.FactType;
import com.flocier.domain.ledger.model.vo.LedgerStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class LedgerFactService {

    /** 新版多账本写入门面。 */
    private final LedgerRecordingService ledgerRecordingService;
    /** 新版多账本聚合查询门面。 */
    private final LedgerQueryService ledgerQueryService;
    private final RuntimeEventMapper runtimeEventMapper;
    private final RuntimeEventPublisher runtimeEventPublisher;

    /** 保持已有直接构造测试和旧调用的兼容性。 */
    public LedgerFactService(LedgerRecordingService ledgerRecordingService, LedgerQueryService ledgerQueryService) {
        this(ledgerRecordingService, ledgerQueryService, new RuntimeEventMapper(), RuntimeEventPublisher.noop());
    }

    @Autowired
    public LedgerFactService(LedgerRecordingService ledgerRecordingService, LedgerQueryService ledgerQueryService,
                             RuntimeEventMapper runtimeEventMapper, RuntimeEventPublisher runtimeEventPublisher) {
        this.ledgerRecordingService = ledgerRecordingService;
        this.ledgerQueryService = ledgerQueryService;
        this.runtimeEventMapper = runtimeEventMapper;
        this.runtimeEventPublisher = runtimeEventPublisher;
    }

    /** 兼容旧入口：记录执行开始。 */
    public void recordRunStarted(AgentContext context, String agentId, String triggerSource) {
        if (AgentRunMode.isReview(context)) return;
        RunLedgerEntity entity = ledgerRecordingService.recordRunStarted(context, agentId, triggerSource);
        if (entity != null) {
            publishSafely(runtimeEventMapper.runStarted(entity, context));
        }
    }

    /** 兼容旧入口：记录执行结束。 */
    public void recordRunFinished(AgentContext context, String status, String summary, String errorCode, String errorMessage) {
        if (AgentRunMode.isReview(context)) return;
        RunLedgerEntity entity = ledgerRecordingService.recordRunFinished(context, status, summary, errorCode, errorMessage);
        if (entity != null) {
            List<String> artifactIds = List.of();
            if (ledgerQueryService != null) {
                try {
                    Optional<LedgerRunView> view = ledgerQueryService.queryRunView(entity.getRunId());
                    if (view != null && view.isPresent()) {
                        artifactIds = view.get().getArtifacts().stream()
                                .filter(artifact -> artifact.getDeleted() == null || artifact.getDeleted() == 0)
                                .filter(artifact -> "visible".equalsIgnoreCase(artifact.getVisibility()))
                                .map(ArtifactLedgerEntity::getArtifactId).distinct().toList();
                    }
                } catch (RuntimeException failure) {
                    log.warn("运行终态产物聚合失败，runId={}", entity.getRunId(), failure);
                }
            }
            publishSafely(runtimeEventMapper.runFinished(entity, artifactIds));
        }
    }

    /** 兼容旧入口：记录 Tool 调用。 */
    public ToolInvocationLedgerEntity recordToolCall(AgentContext context, ToolInvokeRequest request, ToolExecutionResult result) {
        if (AgentRunMode.isReview(context)) return null;
        ToolInvocationLedgerEntity entity = ledgerRecordingService.recordToolCall(context, request, result);
        if (entity != null) {
            publishSafely(runtimeEventMapper.toolResult(entity, result));
        }
        return entity;
    }

    /** 创建 RUNNING 调用事实；持久化成功后才发布工具开始事件。 */
    public ToolInvocationLedgerEntity startToolInvocation(AgentContext context,
                                                           ToolInvokeRequest request,
                                                           String toolName,
                                                           String toolType) {
        if (AgentRunMode.isReview(context)) return null;
        ToolInvocationLedgerEntity entity = ledgerRecordingService.startToolInvocation(context, request, toolName, toolType);
        if (entity != null) {
            publishSafely(runtimeEventMapper.toolStarted(entity));
        }
        return entity;
    }

    /** 工具终态成功持久化后，才发布最终 Tool Result 事件。 */
    public ToolInvocationLedgerEntity finishToolInvocation(ToolInvocationLedgerEntity invocation,
                                                            ToolExecutionResult result) {
        ToolInvocationLedgerEntity entity = ledgerRecordingService.finishToolInvocation(invocation, result);
        if (entity != null) {
            publishSafely(runtimeEventMapper.toolResult(entity, result));
        }
        return entity;
    }

    /** 兼容旧入口：记录 LLM 调用。 */
    public LlmInvocationLedgerEntity recordLlmCall(LlmRequest request, LlmResponse response) {
        if (AgentRunMode.isReview(request.getAttributes())) return null;
        LlmInvocationLedgerEntity entity = ledgerRecordingService.recordLlmCall(request, response);
        if (entity != null) {
            publishSafely(runtimeEventMapper.llmResponse(entity, response));
        }
        return entity;
    }

    /** 兼容旧入口：记录 ToolAsk 调用。 */
    public LlmInvocationLedgerEntity recordToolAsk(ToolAskRequest request, ToolAskResponse response) {
        if (AgentRunMode.isReview(request.getAttributes())) return null;
        LlmInvocationLedgerEntity entity = ledgerRecordingService.recordToolAsk(request, response);
        if (entity != null) {
            publishSafely(runtimeEventMapper.llmResponse(entity, response == null ? null : response.getThought()));
        }
        return entity;
    }

    private void publishSafely(RuntimeEventDraft draft) {
        if (draft == null || !draft.hasRequiredIdentity()) {
            log.debug("runtime event skipped because required identity is incomplete, type={}, runId={}",
                    draft == null ? null : draft.getEventType(), draft == null ? null : draft.getRunId());
            return;
        }
        try {
            runtimeEventPublisher.publish(draft);
        } catch (Exception e) {
            log.warn("runtime event publish skipped, type={}, runId={}, reason={}", draft.getEventType(), draft.getRunId(), e.getMessage(), e);
        }
    }

    /** 查询单个会话账本头信息。 */
    public Optional<SessionLedgerEntity> querySession(String sessionId) {
        return ledgerQueryService.querySession(sessionId);
    }

    /** 查询最近活跃会话列表。 */
    public List<SessionLedgerEntity> queryRecentSessions(LedgerSessionQueryEntity queryEntity) {
        return ledgerQueryService.queryRecentSessions(queryEntity);
    }

    /** 兼容旧查询：将多账本聚合视图转换为旧版统一事实视图。 */
    public List<LedgerFactEntity> query(LedgerFactQueryEntity queryEntity) {
        List<LedgerFactEntity> facts = new ArrayList<>();
        List<RunLedgerEntity> runs = ledgerQueryService.queryRuns(toRunQuery(queryEntity));
        for (RunLedgerEntity run : runs) {
            Optional<LedgerRunView> runViewOptional = ledgerQueryService.queryRunView(run.getRunId());
            if (runViewOptional.isEmpty()) {
                continue;
            }
            LedgerRunView runView = runViewOptional.get();
            facts.add(toRunFact(runView.getRun()));
            runView.getLlmInvocations().stream().map(this::toLlmFact).forEach(facts::add);
            runView.getToolInvocations().stream().map(this::toToolFact).forEach(facts::add);
            runView.getArtifacts().stream().map(this::toArtifactFact).forEach(facts::add);
        }
        return facts.stream()
                .filter(item -> queryEntity == null || queryEntity.getFactType() == null || item.getFactType() == queryEntity.getFactType())
                .filter(item -> queryEntity == null || queryEntity.getSourceType() == null || Objects.equals(queryEntity.getSourceType(), item.getSourceType()))
                .filter(item -> queryEntity == null || queryEntity.getStepNo() == null || Objects.equals(queryEntity.getStepNo(), item.getStepNo()))
                .filter(item -> queryEntity == null || queryEntity.getRequestId() == null || Objects.equals(queryEntity.getRequestId(), item.getRequestId()))
                .filter(item -> queryEntity == null || queryEntity.getSessionId() == null || Objects.equals(queryEntity.getSessionId(), item.getSessionId()))
                .filter(item -> queryEntity == null || queryEntity.getTraceId() == null || Objects.equals(queryEntity.getTraceId(), item.getTraceId()))
                .sorted(Comparator.comparing(LedgerFactEntity::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(queryEntity == null || queryEntity.getLimit() == null || queryEntity.getLimit() <= 0 ? 100 : queryEntity.getLimit())
                .toList();
    }

    private com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity toRunQuery(LedgerFactQueryEntity queryEntity) {
        if (queryEntity == null) {
            return com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity.builder().limit(100).build();
        }
        return com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity.builder()
                .sessionId(queryEntity.getSessionId())
                .requestId(queryEntity.getRequestId())
                .traceId(queryEntity.getTraceId())
                .limit(queryEntity.getLimit())
                .build();
    }

    private LedgerFactEntity toRunFact(RunLedgerEntity run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerSource", run.getTriggerSource());
        payload.put("status", run.getStatus());
        payload.put("errorCode", run.getErrorCode());
        payload.put("errorMessage", run.getErrorMessage());
        return LedgerFactEntity.builder()
                .factId(run.getRunId())
                .factType(FactType.AGENT_EVENT)
                .subjectId(run.getSessionId())
                .subjectType("session")
                .sourceId(run.getRunId())
                .sourceType("run")
                .bizId(run.getRunId())
                .requestId(run.getRequestId())
                .sessionId(run.getSessionId())
                .traceId(run.getTraceId())
                .componentCode(run.getAgentId())
                .inputSummary(queryText(run))
                .outputSummary(run.getSummary())
                .payload(payload)
                .status(mapStatus(run.getStatus()))
                .idempotencyKey(run.getIdempotencyKey())
                .occurredAt(run.getStartedAt())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .ext(copyExt(run.getExt()))
                .build();
    }

    /** query_text is authoritative; this fallback only exposes an already persisted pre-upgrade Run fact. */
    private String queryText(RunLedgerEntity run) {
        if (run == null) {
            return null;
        }
        if (StringUtils.isNotBlank(run.getQueryText())) {
            return run.getQueryText();
        }
        Object legacyQuery = run.getExt() == null ? null : run.getExt().get("queryText");
        String value = legacyQuery == null ? null : String.valueOf(legacyQuery);
        return StringUtils.isBlank(value) ? null : value;
    }

    private LedgerFactEntity toLlmFact(LlmInvocationLedgerEntity item) {
        return LedgerFactEntity.builder()
                .factId(item.getLlmInvocationId())
                .factType(FactType.LLM_CALL)
                .subjectId(item.getSessionId())
                .subjectType("session")
                .sourceId(item.getRequestId())
                .sourceType("llm")
                .bizId(item.getRunId())
                .requestId(item.getRequestId())
                .sessionId(item.getSessionId())
                .traceId(item.getTraceId())
                .stepNo(item.getStepNo())
                .providerCode(item.getProvider())
                .componentCode(item.getModel())
                .inputSummary(item.getRequestSummary())
                .outputSummary(item.getResponseSummary())
                .payload(buildInvocationPayload(item.getRequestPayloadJson(), item.getResponsePayloadJson()))
                .artifactRef(item.getArtifactRef())
                .usageType("token")
                .usageQuantity(item.getTotalTokens() == null ? null : item.getTotalTokens().longValue())
                .status(mapStatus(item.getStatus()))
                .idempotencyKey(item.getIdempotencyKey())
                .occurredAt(item.getStartedAt())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .ext(copyExt(item.getExt()))
                .build();
    }

    private LedgerFactEntity toToolFact(ToolInvocationLedgerEntity item) {
        return LedgerFactEntity.builder()
                .factId(item.getToolInvocationId())
                .factType(FactType.TOOL_CALL)
                .subjectId(item.getSessionId())
                .subjectType("session")
                .sourceId(item.getToolInvocationId())
                .sourceType(item.getToolType())
                .bizId(item.getRunId())
                .requestId(item.getRequestId())
                .sessionId(item.getSessionId())
                .traceId(item.getTraceId())
                .stepNo(item.getStepNo())
                .componentCode(item.getToolName())
                .inputSummary(item.getRequestSummary())
                .outputSummary(item.getResponseSummary())
                .payload(buildInvocationPayload(item.getRequestPayloadJson(), item.getResponsePayloadJson()))
                .artifactRef(item.getArtifactRef())
                .usageType("tool_call")
                .usageQuantity(1L)
                .status(mapStatus(item.getStatus()))
                .idempotencyKey(item.getIdempotencyKey())
                .occurredAt(item.getFinishedAt())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .ext(copyExt(item.getExt()))
                .build();
    }

    private LedgerFactEntity toArtifactFact(ArtifactLedgerEntity item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolInvocationId", item.getToolInvocationId());
        payload.put("toolCallId", item.getToolCallId());
        payload.put("artifactRole", item.getArtifactRole());
        payload.put("visibility", item.getVisibility());
        payload.put("sourceName", item.getSourceName());
        payload.put("fileName", item.getFileName());
        payload.put("storageType", item.getStorageType());
        payload.put("storageRef", item.getStorageRef());
        payload.put("storageKey", item.getStorageKey());
        payload.put("downloadUrl", item.getDownloadUrl());
        payload.put("previewUrl", item.getPreviewUrl());
        payload.put("mimeType", item.getMimeType());
        payload.put("size", firstNonNull(item.getFileSize(), item.getSize()));
        payload.put("fileSize", item.getFileSize());
        payload.put("fileHash", item.getFileHash());
        payload.put("metadataJson", item.getMetadataJson());
        return LedgerFactEntity.builder()
                .factId(item.getArtifactId())
                .factType(FactType.ARTIFACT)
                .subjectId(item.getSessionId())
                .subjectType("session")
                .sourceId(item.getSourceId())
                .sourceType(item.getSourceType())
                .bizId(item.getRunId())
                .requestId(item.getRequestId())
                .sessionId(item.getSessionId())
                .traceId(item.getTraceId())
                .componentCode(item.getArtifactType())
                .inputSummary(firstNonBlank(item.getFileName(), item.getArtifactName()))
                .outputSummary(item.getContentSummary())
                .payload(payload)
                .artifactRef(firstNonBlank(item.getStorageRef(), item.getStorageKey(), item.getDownloadUrl(), item.getPreviewUrl()))
                .status(LedgerStatus.RECORDED)
                .idempotencyKey(item.getIdempotencyKey())
                .occurredAt(item.getCreatedAt())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .ext(copyExt(item.getExt()))
                .build();
    }

    private LedgerStatus mapStatus(String status) {
        if ("COMPLETED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status) || "RECORDED".equalsIgnoreCase(status)) {
            return LedgerStatus.RECORDED;
        }
        if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
            return LedgerStatus.ADJUSTED;
        }
        return LedgerStatus.RECORDED;
    }

    private Map<String, Object> buildInvocationPayload(String requestPayloadJson, String responsePayloadJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (requestPayloadJson == null || requestPayloadJson.isBlank()
                || responsePayloadJson == null || responsePayloadJson.isBlank()) {
            payload.put("completeFactAvailable", false);
            payload.put("completeFactUnavailableReason", "历史记录未保存完整事实载荷");
            return payload;
        }
        try {
            payload.put("completeFactAvailable", true);
            payload.put("request", JSON.parse(requestPayloadJson));
            payload.put("response", JSON.parse(responsePayloadJson));
            return payload;
        } catch (RuntimeException exception) {
            payload.clear();
            payload.put("completeFactAvailable", false);
            payload.put("completeFactUnavailableReason", "完整事实载荷格式无效");
            return payload;
        }
    }

    private Map<String, Object> copyExt(Map<String, Object> ext) {
        return ext == null ? new LinkedHashMap<>() : new LinkedHashMap<>(ext);
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private Long firstNonNull(Long... candidates) {
        if (candidates == null) {
            return null;
        }
        for (Long candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}
