package com.flocier.domain.ledger.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.response.LlmUsage;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.ILlmInvocationLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IRunLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.ISessionLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IToolInvocationLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerStats;
import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.model.vo.SessionLedgerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerRecordingService {

    private static final String ARTIFACT_TYPE_LLM_RESPONSE = "llm_response";
    private static final String ARTIFACT_TYPE_TOOL_RESULT = "tool_result";
    private static final String STORAGE_TYPE_INLINE_SUMMARY = "inline_summary";
    private static final int PAYLOAD_SCHEMA_VERSION = 1;
    private static final int TOOL_PAYLOAD_SCHEMA_VERSION = 2;
    private static final Set<String> SENSITIVE_LEDGER_KEYS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "password", "passwd", "secret", "api_key", "apikey",
            "access_token", "accesstoken", "refresh_token", "refreshtoken",
            "client_secret", "clientsecret", "headers", "environment", "env"
    );
    /** Run 主账本仓储。 */
    private final ISessionLedgerRepository sessionLedgerRepository;
    private final IRunLedgerRepository runLedgerRepository;
    /** LLM 调用账本仓储。 */
    private final ILlmInvocationLedgerRepository llmInvocationLedgerRepository;
    /** Tool 调用账本仓储。 */
    private final IToolInvocationLedgerRepository toolInvocationLedgerRepository;
    /** 产物账本仓储。 */
    private final IArtifactLedgerRepository artifactLedgerRepository;

    /** 记录一次执行开始。 */
    public RunLedgerEntity recordRunStarted(AgentContext context, String agentId, String triggerSource) {
        String runId = resolveRunId(context);
        String sessionId = context == null ? null : context.getSessionId();
        String requestId = context == null ? null : context.getRequestId();
        String traceId = resolveTraceId(context, requestId, sessionId);
        String idempotencyKey = String.join(":", "run", "start", defaultString(runId));
        Optional<RunLedgerEntity> existingStart = runLedgerRepository.findByIdempotencyKey(idempotencyKey);
        if (existingStart.isPresent()) {
            RunLedgerEntity entity = existingStart.get();
            String recoveredQuery = firstNonBlank(effectiveQueryText(entity), queryText(context));
            if (StringUtils.isBlank(entity.getQueryText()) && StringUtils.isNotBlank(recoveredQuery)) {
                entity.setQueryText(recoveredQuery);
                entity.setUpdatedAt(Instant.now());
                runLedgerRepository.update(entity);
            }
            log.info("ledger run start skipped, runId={}, requestId={}, reason=idempotent", runId, requestId);
            return entity;
        }
        Instant now = Instant.now();
        touchSessionOnRunStart(sessionId, agentId, requestId, context == null ? null : context.getQuery(), now);
        log.info("ledger run start record, agentId={}, triggerSource={}, sessionId={}, runId={}, requestId={}, traceId={}",
                agentId,
                triggerSource,
                sessionId,
                runId,
                requestId,
                traceId);
        RunLedgerEntity entity = RunLedgerEntity.builder()
                .runId(runId)
                .sessionId(sessionId)
                .requestId(requestId)
                .traceId(traceId)
                .agentId(agentId)
                .triggerSource(triggerSource)
                .status("RUNNING")
                .queryText(queryText(context))
                .startedAt(now)
                .idempotencyKey(idempotencyKey)
                .createdAt(now)
                .updatedAt(now)
                .build();
        runLedgerRepository.save(entity);
        return entity;
    }

    /** 记录一次执行结束，并回填最终状态和摘要。 */
    public RunLedgerEntity recordRunFinished(AgentContext context, String status, String summary, String errorCode, String errorMessage) {
        String runId = resolveRunId(context);
        String sessionId = context == null ? null : context.getSessionId();
        String requestId = context == null ? null : context.getRequestId();
        String traceId = resolveTraceId(context, requestId, sessionId);
        Instant now = Instant.now();
        log.info("ledger run finish record, sessionId={}, runId={}, requestId={}, traceId={}, status={}, errorCode={}, summaryPreview={}",
                sessionId,
                runId,
                requestId,
                traceId,
                status,
                errorCode,
                shorten(summary));
        Optional<RunLedgerEntity> existingRun = runLedgerRepository.findByRunId(runId);
        RunLedgerEntity entity = existingRun
                .orElseGet(() -> RunLedgerEntity.builder()
                        .runId(runId)
                        .sessionId(sessionId)
                        .requestId(requestId)
                        .traceId(traceId)
                        .startedAt(now)
                        .idempotencyKey(String.join(":", "run", "finish", defaultString(runId)))
                        .createdAt(now)
                        .build());
        entity.setStatus(StringUtils.defaultIfBlank(status, "COMPLETED"));
        entity.setSummary(summary);
        entity.setErrorCode(errorCode);
        entity.setErrorMessage(errorMessage);
        entity.setQueryText(firstNonBlank(effectiveQueryText(entity), queryText(context)));
        entity.setFinishedAt(now);
        entity.setUpdatedAt(now);
        if (existingRun.isPresent()) {
            runLedgerRepository.update(entity);
        } else {
            runLedgerRepository.save(entity);
        }
        touchSessionOnRunFinish(sessionId, requestId, status, summary, now);
        return entity;
    }

    /** 记录一次 LLM 调用事实。 */
    public LlmInvocationLedgerEntity recordLlmCall(LlmRequest request, LlmResponse response) {
        Map<String, Object> attributes = request == null || request.getAttributes() == null ? Map.of() : request.getAttributes();
        String requestId = stringAttr(attributes, "requestId");
        String sessionId = stringAttr(attributes, "sessionId");
        String traceId = firstNonBlank(stringAttr(attributes, "traceId"), requestId, sessionId, UUID.randomUUID().toString());
        String runId = resolveRunId(stringAttr(attributes, "runId"), requestId, sessionId, traceId);
        String scene = stringAttr(attributes, "scene");
        String llmCallId = firstNonBlank(
                stringAttr(attributes, "llmCallId"),
                stringAttr(attributes, "callId"),
                stringAttr(attributes, "responseId")
        );
        ensureRunExists(runId, sessionId, requestId, traceId, stringAttr(attributes, "agentId"), "llm");
        Map<String, Object> requestPayload = buildLlmRequestPayload(request);
        String requestPayloadJson = toPayloadJson(requestPayload);
        String responsePayloadJson = toPayloadJson(buildLlmResponsePayload(response));
        String idempotencyKey = buildLlmIdempotencyKey(
                canonicalJson(requestPayload),
                runId,
                traceId,
                requestId,
                scene,
                intAttr(attributes, "step"),
                llmCallId
        );
        Optional<LlmInvocationLedgerEntity> existingInvocation = llmInvocationLedgerRepository.findByIdempotencyKey(idempotencyKey);
        if (existingInvocation.isPresent()) {
            log.info("ledger llm record skipped, runId={}, requestId={}, traceId={}, scene={}, reason=idempotent",
                    runId, requestId, traceId, scene);
            return existingInvocation.get();
        }

        Instant now = Instant.now();
        LlmUsage usage = response == null ? null : response.getUsage();
        log.info("ledger llm record, sessionId={}, runId={}, requestId={}, traceId={}, scene={}, model={}, provider={}, step={}, totalTokens={}",
                sessionId,
                runId,
                requestId,
                traceId,
                scene,
                response == null ? null : response.getModel(),
                response == null ? null : response.getProvider(),
                intAttr(attributes, "step"),
                usage == null ? null : usage.getTotalTokens());
        LlmInvocationLedgerEntity entity = LlmInvocationLedgerEntity.builder()
                .llmInvocationId(UUID.randomUUID().toString())
                .runId(runId)
                .sessionId(sessionId)
                .requestId(requestId)
                .traceId(traceId)
                .stepNo(intAttr(attributes, "step"))
                .provider(response == null ? null : response.getProvider())
                .model(response == null ? null : response.getModel())
                .scene(scene)
                .requestSummary(buildMessageSummary(request))
                .responseSummary(shorten(response == null ? null : response.getText()))
                .requestPayloadJson(requestPayloadJson)
                .responsePayloadJson(responsePayloadJson)
                .inputTokens(usage == null ? null : usage.getPromptTokens())
                .outputTokens(usage == null ? null : usage.getCompletionTokens())
                .totalTokens(usage == null ? null : usage.getTotalTokens())
                .status("RECORDED")
                .startedAt(now)
                .finishedAt(now)
                .idempotencyKey(idempotencyKey)
                .createdAt(now)
                .updatedAt(now)
                .ext(buildLlmExt(attributes, llmCallId))
                .build();
        llmInvocationLedgerRepository.save(entity);
        return entity;
    }

    /** 记录一次 Tool 调用事实。 */
    public ToolInvocationLedgerEntity recordToolCall(AgentContext context, ToolInvokeRequest request, ToolExecutionResult result) {
        String traceId = resolveTraceId(context, request == null ? null : request.getRequestId(), request == null ? null : request.getSessionId());
        String sessionId = firstNonBlank(context == null ? null : context.getSessionId(), request == null ? null : request.getSessionId());
        String requestId = firstNonBlank(context == null ? null : context.getRequestId(), request == null ? null : request.getRequestId());
        String runId = resolveRunId(
                context == null ? null : stringValue(context.getAttribute("runId")),
                requestId,
                sessionId,
                traceId
        );
        String toolCallId = firstNonBlank(
                request == null ? null : request.getToolCallId(),
                result == null ? null : result.getToolCallId()
        );
        String llmInvocationId = firstNonBlank(
                request == null || request.getMetadata() == null ? null : stringValue(request.getMetadata().get("llmInvocationId")),
                context == null ? null : context.getCurrentLlmInvocationId()
        );
        Integer dispatchOrder = integerValue(request == null || request.getMetadata() == null
                ? null
                : request.getMetadata().get("dispatchOrder"));
        ensureRunExists(runId, sessionId, requestId, traceId, stringAttr(copyAttributes(context), "agentId"), "tool");
        String requestPayloadJson = toPayloadJson(buildToolRequestPayload(request, runId, traceId, sessionId, requestId));
        String responsePayloadJson = toPayloadJson(buildToolResponsePayload(result));
        String idempotencyKey = String.join(":",
                "tool",
                defaultString(traceId),
                defaultString(toolCallId),
                String.valueOf(request == null ? null : request.getStep()));
        Optional<ToolInvocationLedgerEntity> existingToolInvocation = toolInvocationLedgerRepository.findByIdempotencyKey(idempotencyKey);
        if (existingToolInvocation.isPresent()) {
            log.info("ledger tool record skipped, runId={}, requestId={}, traceId={}, toolCallId={}, reason=idempotent",
                    runId, requestId, traceId, toolCallId);
            return existingToolInvocation.get();
        }

        Instant now = Instant.now();
        log.info("ledger tool record, sessionId={}, runId={}, requestId={}, traceId={}, toolCallId={}, toolName={}, step={}, status={}, durationMs={}",
                sessionId,
                runId,
                requestId,
                traceId,
                toolCallId,
                result == null ? null : result.getToolName(),
                context == null ? null : context.getCurrentStep(),
                result == null ? null : result.getStatus(),
                result == null ? null : result.getDurationMs());
        ToolInvocationLedgerEntity entity = ToolInvocationLedgerEntity.builder()
                .toolInvocationId(UUID.randomUUID().toString())
                .toolCallId(toolCallId)
                .llmInvocationId(llmInvocationId)
                .dispatchOrder(dispatchOrder)
                .runId(runId)
                .sessionId(sessionId)
                .requestId(requestId)
                .traceId(traceId)
                .stepNo(context == null ? null : context.getCurrentStep())
                .toolName(result == null ? null : result.getToolName())
                .toolType(result == null ? null : result.getSourceType())
                .requestSummary(shortenJson(request == null ? null : request.getArguments()))
                .responseSummary(result == null ? null : shorten(result.getPreferredObservationText()))
                .requestPayloadJson(requestPayloadJson)
                .responsePayloadJson(responsePayloadJson)
                .latencyMs(result == null ? null : result.getDurationMs())
                .status(result == null ? null : result.getStatus())
                .startedAt(result == null ? now : defaultInstant(result.getStartedAt(), now))
                .finishedAt(result == null ? now : defaultInstant(result.getFinishedAt(), now))
                .errorCode(result == null ? null : result.getErrorCode())
                .errorMessage(result == null ? null : result.getErrorMessage())
                .artifactRef(result == null || result.getArtifactRefs() == null || result.getArtifactRefs().isEmpty()
                        ? null
                        : result.getArtifactRefs().get(0))
                .idempotencyKey(idempotencyKey)
                .createdAt(now)
                .updatedAt(now)
                .ext(buildToolExt(request, result, context))
                .build();
        toolInvocationLedgerRepository.save(entity);
        return entity;

    }

    /** 预创建工具调用事实，供普通工具与自持生命周期的 Rich Tool 共用。 */
    public ToolInvocationLedgerEntity startToolInvocation(AgentContext context,
                                                           ToolInvokeRequest request,
                                                           String toolName,
                                                           String toolType) {
        String traceId = resolveTraceId(context, request == null ? null : request.getRequestId(), request == null ? null : request.getSessionId());
        String sessionId = firstNonBlank(context == null ? null : context.getSessionId(), request == null ? null : request.getSessionId());
        String requestId = firstNonBlank(context == null ? null : context.getRequestId(), request == null ? null : request.getRequestId());
        String runId = resolveRunId(context == null ? null : stringValue(context.getAttribute("runId")), requestId, sessionId, traceId);
        String toolCallId = request == null ? null : request.getToolCallId();
        String llmInvocationId = firstNonBlank(
                request == null || request.getMetadata() == null ? null : stringValue(request.getMetadata().get("llmInvocationId")),
                context == null ? null : context.getCurrentLlmInvocationId());
        Integer dispatchOrder = integerValue(request == null || request.getMetadata() == null ? null : request.getMetadata().get("dispatchOrder"));
        ensureRunExists(runId, sessionId, requestId, traceId, stringAttr(copyAttributes(context), "agentId"), "tool");
        String idempotencyKey = String.join(":", "tool", "start", defaultString(traceId), defaultString(toolCallId),
                String.valueOf(request == null ? null : request.getStep()));
        Optional<ToolInvocationLedgerEntity> existing = toolInvocationLedgerRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        ToolInvocationLedgerEntity entity = ToolInvocationLedgerEntity.builder()
                .toolInvocationId(UUID.randomUUID().toString())
                .toolCallId(toolCallId)
                .llmInvocationId(llmInvocationId)
                .dispatchOrder(dispatchOrder)
                .runId(runId)
                .sessionId(sessionId)
                .requestId(requestId)
                .traceId(traceId)
                .stepNo(context == null ? null : context.getCurrentStep())
                .toolName(toolName)
                .toolType(toolType)
                .requestSummary(shortenJson(request == null ? null : request.getArguments()))
                .requestPayloadJson(toPayloadJson(buildToolRequestPayload(request, runId, traceId, sessionId, requestId)))
                .status("RUNNING")
                .startedAt(now)
                .idempotencyKey(idempotencyKey)
                .createdAt(now)
                .updatedAt(now)
                .ext(buildToolLifecycleExt(request, context, toolType))
                .build();
        toolInvocationLedgerRepository.save(entity);
        return entity;
    }

    /** 将已创建的工具调用更新为唯一终态，不新增第二条 Tool Invocation。 */
    public ToolInvocationLedgerEntity finishToolInvocation(ToolInvocationLedgerEntity entity, ToolExecutionResult result) {
        if (entity == null || StringUtils.isBlank(entity.getToolInvocationId())) {
            throw new IllegalArgumentException("A persisted tool invocation is required before finishing a tool");
        }
        if (!"RUNNING".equalsIgnoreCase(entity.getStatus())) {
            return entity;
        }
        Instant finishedAt = result == null ? Instant.now() : defaultInstant(result.getFinishedAt(), Instant.now());
        ToolInvocationLedgerEntity finished = entity.toBuilder()
                .responseSummary(result == null ? null : shorten(result.getPreferredObservationText()))
                .responsePayloadJson(toPayloadJson(buildToolResponsePayload(result)))
                .latencyMs(result == null ? null : result.getDurationMs())
                .status(result == null ? "FAILED" : result.getStatus())
                .finishedAt(finishedAt)
                .errorCode(result == null ? "tool_missing_result" : result.getErrorCode())
                .errorMessage(result == null ? "Tool did not return a final result" : result.getErrorMessage())
                .artifactRef(result == null || result.getArtifactRefs() == null || result.getArtifactRefs().isEmpty()
                        ? null : result.getArtifactRefs().get(0))
                .updatedAt(Instant.now())
                .build();
        toolInvocationLedgerRepository.update(finished);
        return finished;
    }

    private Map<String, Object> buildToolLifecycleExt(ToolInvokeRequest request, AgentContext context, String toolType) {
        Map<String, Object> ext = buildToolExt(request, null, context);
        ext.put("lifecycle", "rich_python".equalsIgnoreCase(toolType) ? "rich_tool" : "two_phase");
        return ext;
    }

    /** 将 tool ask 视为一类特殊的 LLM 调用，统一写入账本。 */
    public LlmInvocationLedgerEntity recordToolAsk(ToolAskRequest request, ToolAskResponse response) {
        Map<String, Object> responseMetadata = response == null || response.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(response.getMetadata());
        responseMetadata.put("toolCalls", serializeToolCalls(response == null ? null : response.getToolCalls()));
        LlmRequest syntheticRequest = LlmRequest.builder()
                .model(request == null ? null : request.getModel())
                .messages(request == null ? null : request.getMessages())
                .temperature(request == null ? null : request.getTemperature())
                .maxTokens(request == null ? null : request.getMaxTokens())
                .attributes(request == null || request.getAttributes() == null ? Map.of() : request.getAttributes())
                .build();
        LlmResponse syntheticResponse = LlmResponse.builder()
                .model(response == null ? null : response.getModel())
                .provider(response == null ? null : response.getProvider())
                .text(response == null ? null : response.getThought())
                .finishReason(response == null ? null : response.getFinishReason())
                .usage(response == null ? null : response.getUsage())
                .metadata(responseMetadata)
                .build();
        return recordLlmCall(syntheticRequest, syntheticResponse);
    }

    /** 记录可复用、可寻址的运行产物。 */
    public ArtifactLedgerEntity recordArtifact(ArtifactLedgerEntity entity) {
        if (entity == null) {
            return null;
        }
        String rejectionReason = artifactRejectionReason(entity);
        if (rejectionReason != null) {
            log.warn("ledger artifact rejected, artifactType={}, storageType={}, storageRef={}, storageKey={}, downloadUrl={}, previewUrl={}, sourceType={}, sourceId={}, toolInvocationId={}, toolCallId={}, runId={}, requestId={}, reason={}",
                    entity.getArtifactType(),
                    entity.getStorageType(),
                    entity.getStorageRef(),
                    entity.getStorageKey(),
                    entity.getDownloadUrl(),
                    entity.getPreviewUrl(),
                    entity.getSourceType(),
                    entity.getSourceId(),
                    entity.getToolInvocationId(),
                    entity.getToolCallId(),
                    entity.getRunId(),
                    entity.getRequestId(),
                    rejectionReason);
            return null;
        }
        if (artifactLedgerRepository.findByIdempotencyKey(entity.getIdempotencyKey()).isPresent()) {
            log.info("ledger artifact skipped, artifactType={}, runId={}, requestId={}, reason=idempotent",
                    entity.getArtifactType(),
                    entity.getRunId(),
                    entity.getRequestId());
            return artifactLedgerRepository.findByIdempotencyKey(entity.getIdempotencyKey()).orElse(null);
        }
        log.info("ledger artifact record, artifactType={}, artifactName={}, sourceType={}, sourceId={}, sessionId={}, runId={}, requestId={}, traceId={}",
                entity.getArtifactType(),
                entity.getArtifactName(),
                entity.getSourceType(),
                entity.getSourceId(),
                entity.getSessionId(),
                entity.getRunId(),
                entity.getRequestId(),
                entity.getTraceId());
        artifactLedgerRepository.save(entity);
        return entity;
    }

    private Map<String, Object> buildLlmRequestPayload(LlmRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", PAYLOAD_SCHEMA_VERSION);
        payload.put("model", request == null ? null : request.getModel());
        payload.put("temperature", request == null ? null : request.getTemperature());
        payload.put("maxTokens", request == null ? null : request.getMaxTokens());
        payload.put("stream", request == null ? null : request.getStream());
        payload.put("messages", request == null ? List.of() : serializeLlmMessages(request.getMessages()));
        payload.put("attributes", request == null ? Map.of() : sanitizeForLedger(request.getAttributes()));
        return payload;
    }

    private Map<String, Object> buildLlmResponsePayload(LlmResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", PAYLOAD_SCHEMA_VERSION);
        payload.put("responseAvailable", response != null);
        payload.put("provider", response == null ? null : response.getProvider());
        payload.put("model", response == null ? null : response.getModel());
        payload.put("text", response == null ? null : response.getText());
        payload.put("finishReason", response == null ? null : response.getFinishReason());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptTokens", response == null || response.getUsage() == null ? null : response.getUsage().getPromptTokens());
        usage.put("completionTokens", response == null || response.getUsage() == null ? null : response.getUsage().getCompletionTokens());
        usage.put("totalTokens", response == null || response.getUsage() == null ? null : response.getUsage().getTotalTokens());
        payload.put("usage", usage);
        payload.put("metadata", response == null ? Map.of() : sanitizeForLedger(response.getMetadata()));
        return payload;
    }

    private List<Map<String, Object>> serializeLlmMessages(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (LlmMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message == null || message.getRole() == null ? null : message.getRole().name());
            item.put("content", message == null ? null : message.getContent());
            item.put("toolCalls", message == null ? List.of() : serializeToolCalls(message.getToolCalls()));
            item.put("toolCallId", message == null ? null : message.getToolCallId());
            item.put("metadata", message == null ? Map.of() : sanitizeForLedger(message.getMetadata()));
            serialized.add(item);
        }
        return serialized;
    }

    private List<Map<String, Object>> serializeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", toolCall == null ? null : toolCall.getId());
            item.put("type", toolCall == null ? null : toolCall.getType());
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", toolCall == null || toolCall.getFunction() == null ? null : toolCall.getFunction().getName());
            function.put("arguments", toolCall == null || toolCall.getFunction() == null ? null : toolCall.getFunction().getArguments());
            item.put("function", function);
            serialized.add(item);
        }
        return serialized;
    }

    private Map<String, Object> buildToolRequestPayload(ToolInvokeRequest request,
                                                         String runId,
                                                         String traceId,
                                                         String sessionId,
                                                         String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", TOOL_PAYLOAD_SCHEMA_VERSION);
        payload.put("toolName", request == null ? null : request.getToolName());
        payload.put("toolCallId", request == null ? null : request.getToolCallId());
        payload.put("arguments", request == null ? Map.of() : sanitizeForLedger(request.getArguments()));
        payload.put("metadata", request == null ? Map.of() : sanitizeForLedger(request.getMetadata()));
        payload.put("sourceHint", request == null ? null : request.getSourceHint());
        payload.put("requestId", requestId);
        payload.put("sessionId", sessionId);
        payload.put("runId", runId);
        payload.put("traceId", traceId);
        payload.put("step", request == null ? null : request.getStep());
        return payload;
    }

    private Map<String, Object> buildToolResponsePayload(ToolExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", TOOL_PAYLOAD_SCHEMA_VERSION);
        payload.put("resultAvailable", result != null);
        payload.put("toolName", result == null ? null : result.getToolName());
        payload.put("toolCallId", result == null ? null : result.getToolCallId());
        payload.put("sourceType", result == null ? null : result.getSourceType());
        payload.put("success", result != null && result.isSuccess());
        payload.put("status", result == null ? null : result.getStatus());
        payload.put("errorCode", result == null ? null : result.getErrorCode());
        payload.put("errorMessage", result == null ? null : result.getErrorMessage());
        payload.put("llmObservation", result == null ? null : result.getLlmObservation());
        payload.put("result", result == null ? null : sanitizeForLedger(canonicalToolResult(result)));
        payload.put("artifactRefs", result == null ? List.of() : sanitizeForLedger(result.getArtifactRefs()));
        payload.put("attributes", result == null ? Map.of() : sanitizeForLedger(result.getAttributes()));
        payload.put("startedAt", result == null || result.getStartedAt() == null ? null : result.getStartedAt().toString());
        payload.put("finishedAt", result == null || result.getFinishedAt() == null ? null : result.getFinishedAt().toString());
        payload.put("durationMs", result == null ? null : result.getDurationMs());
        return payload;
    }

    private Object sanitizeForLedger(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                sanitized.put(key, isSensitiveLedgerKey(key) ? "[REDACTED]" : sanitizeForLedger(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(sanitizeForLedger(item));
            }
            return sanitized;
        }
        return value;
    }

    private boolean isSensitiveLedgerKey(String key) {
        if (key == null) {
            return false;
        }
        return SENSITIVE_LEDGER_KEYS.contains(key.replace('-', '_').toLowerCase(Locale.ROOT));
    }

    private String toPayloadJson(Map<String, Object> payload) {
        return JSON.toJSONString(payload, SerializerFeature.WriteMapNullValue);
    }

    private String canonicalJson(Map<String, Object> payload) {
        return JSON.toJSONString(payload, SerializerFeature.MapSortField, SerializerFeature.WriteMapNullValue);
    }

    private Map<String, Object> buildToolExt(ToolInvokeRequest request, ToolExecutionResult result, AgentContext context) {
        Map<String, Object> ext = new LinkedHashMap<>();
        putIfPresent(ext, "agentId", context == null ? null : context.getAttribute("agentId"));
        if (result != null && result.getAttributes() != null) {
            putIfPresent(ext, "skillName", result.getAttributes().get("skillName"));
            putIfPresent(ext, "skillExecutionId", result.getAttributes().get("skillExecutionId"));
            putIfPresent(ext, "skillResultSummary", result.getAttributes().get("skillResultSummary"));
            putIfPresent(ext, "usedTools", result.getAttributes().get("usedTools"));
        }
        return ext;
    }

    private Object canonicalToolResult(ToolExecutionResult result) {
        if (result.getStructuredOutput() != null
                && (!(result.getStructuredOutput() instanceof Map<?, ?> map) || !map.isEmpty())) {
            return result.getStructuredOutput();
        }
        if (result.getOutputText() != null && !result.getOutputText().isBlank()) {
            return Map.of("text", result.getOutputText());
        }
        if (result.getLedgerFacts() != null && !result.getLedgerFacts().isEmpty()) {
            return Map.of("ledgerFacts", result.getLedgerFacts());
        }
        return Map.of();
    }

    private Map<String, Object> buildLlmExt(Map<String, Object> attributes, String llmCallId) {
        Map<String, Object> ext = new LinkedHashMap<>();
        Object sanitizedAttributes = sanitizeForLedger(attributes == null ? Map.of() : attributes);
        if (sanitizedAttributes instanceof Map<?, ?> sanitizedMap) {
            sanitizedMap.forEach((key, value) -> ext.put(String.valueOf(key), value));
        }
        putIfPresent(ext, "llmCallId", llmCallId);
        return ext;
    }

    private Map<String, Object> copyAttributes(AgentContext context) {
        if (context == null || context.getAttributes() == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(context.getAttributes());
    }

    private String resolveRunId(AgentContext context) {
        return resolveRunId(
                context == null ? null : stringValue(context.getAttribute("runId")),
                context == null ? null : context.getRequestId(),
                context == null ? null : context.getSessionId(),
                context == null ? null : context.getTraceId()
        );
    }

    private String resolveRunId(String explicitRunId, String requestId, String sessionId, String traceId) {
        return firstNonBlank(explicitRunId, requestId, sessionId, traceId, UUID.randomUUID().toString());
    }

    private String resolveTraceId(AgentContext context, String requestId, String sessionId) {
        if (context != null && StringUtils.isNotBlank(context.getTraceId())) {
            return context.getTraceId();
        }
        return firstNonBlank(requestId, sessionId, UUID.randomUUID().toString());
    }

    private String buildMessageSummary(LlmRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        request.getMessages().stream().limit(3).forEach(message -> {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append("[")
                    .append(message.getRole())
                    .append("] ")
                    .append(shorten(message.getContent()));
        });
        return builder.toString();
    }

    private String shortenJson(Object value) {
        return shorten(value == null ? null : JSON.toJSONString(value));
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 300) {
            return normalized;
        }
        return normalized.substring(0, 300) + "...";
    }

    private String buildLlmIdempotencyKey(String canonicalRequestPayload,
                                          String runId,
                                          String traceId,
                                          String requestId,
                                          String scene,
                                          Integer stepNo,
                                          String llmCallId) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("runId", runId);
        scope.put("traceId", traceId);
        scope.put("requestId", requestId);
        scope.put("scene", scene);
        scope.put("stepNo", stepNo);
        scope.put("llmCallId", llmCallId);
        scope.put("request", StringUtils.isNotBlank(llmCallId) ? null : canonicalRequestPayload);
        return "llm:" + sha256(canonicalJson(scope)).substring(0, 48);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(defaultString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 算法不可用", exception);
        }
    }

    private String stringAttr(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer intAttr(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integer) {
            return integer;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Instant defaultInstant(Instant value, Instant fallback) {
        return value == null ? fallback : value;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (StringUtils.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || value == null) {
            return;
        }
        target.put(key, value);
    }

    private String artifactRejectionReason(ArtifactLedgerEntity entity) {
        if (isLegacyPseudoArtifact(entity)) {
            return "legacy_pseudo_artifact";
        }
        if (!hasArtifactLocation(entity)) {
            return "missing_storage_location";
        }
        if (StringUtils.isBlank(entity.getSourceType())
                && StringUtils.isBlank(entity.getSourceId())
                && StringUtils.isBlank(entity.getToolInvocationId())
                && StringUtils.isBlank(entity.getToolCallId())) {
            return "missing_source";
        }
        if (StringUtils.isBlank(entity.getRunId())
                && StringUtils.isBlank(entity.getRequestId())
                && StringUtils.isBlank(entity.getTraceId())) {
            return "missing_trace_context";
        }
        return null;
    }

    private boolean isLegacyPseudoArtifact(ArtifactLedgerEntity entity) {
        return equalsIgnoreCase(ARTIFACT_TYPE_LLM_RESPONSE, entity.getArtifactType())
                || equalsIgnoreCase(ARTIFACT_TYPE_TOOL_RESULT, entity.getArtifactType())
                || equalsIgnoreCase(STORAGE_TYPE_INLINE_SUMMARY, entity.getStorageType());
    }

    private boolean hasArtifactLocation(ArtifactLedgerEntity entity) {
        return StringUtils.isNotBlank(entity.getStorageRef())
                || StringUtils.isNotBlank(entity.getStorageKey())
                || StringUtils.isNotBlank(entity.getDownloadUrl())
                || StringUtils.isNotBlank(entity.getPreviewUrl());
    }

    private boolean equalsIgnoreCase(String expected, String actual) {
        return expected.equalsIgnoreCase(defaultString(actual));
    }

    /** 当主链路未显式创建 run 时，按最小信息补一条隐式 run，保证子账本可关联。 */
    private void touchSessionOnRunStart(String sessionId, String agentId, String requestId, String queryText, Instant now) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        boolean exists = sessionLedgerRepository.findBySessionId(sessionId).isPresent();
        SessionLedgerEntity session = sessionLedgerRepository.findBySessionId(sessionId)
                .orElseGet(() -> SessionLedgerEntity.builder()
                        .sessionId(sessionId)
                        .agentId(agentId)
                        .status(SessionLedgerStatus.ACTIVE.name())
                        .runCount(0)
                        .finishedRunCount(0)
                        .failedRunCount(0)
                        .startedAt(now)
                        .lastActiveAt(now)
                        .idempotencyKey(String.join(":", "session", "create", defaultString(sessionId)))
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
        session.setAgentId(firstNonBlank(agentId, session.getAgentId()));
        session.setStatus(SessionLedgerStatus.ACTIVE.name());
        session.setLatestRequestId(firstNonBlank(requestId, session.getLatestRequestId()));
        session.setLatestQueryText(firstNonBlank(queryText, session.getLatestQueryText()));
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        session.setRunCount(defaultInt(session.getRunCount()) + 1);
        if (exists) {
            sessionLedgerRepository.update(session);
        } else {
            sessionLedgerRepository.save(session);
        }
    }

    private void touchSessionOnRunFinish(String sessionId, String requestId, String status, String summary, Instant now) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        RunLedgerStats stats = runLedgerRepository.countBySessionId(sessionId);
        boolean exists = sessionLedgerRepository.findBySessionId(sessionId).isPresent();
        SessionLedgerEntity session = sessionLedgerRepository.findBySessionId(sessionId)
                .orElseGet(() -> SessionLedgerEntity.builder()
                        .sessionId(sessionId)
                        .status(SessionLedgerStatus.ACTIVE.name())
                        .runCount(0)
                        .finishedRunCount(0)
                        .failedRunCount(0)
                        .startedAt(now)
                        .lastActiveAt(now)
                        .idempotencyKey(String.join(":", "session", "create", defaultString(sessionId)))
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
        session.setLatestRequestId(firstNonBlank(requestId, session.getLatestRequestId()));
        session.setLatestSummaryText(firstNonBlank(summary, session.getLatestSummaryText()));
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        session.setRunCount(defaultInt(stats.getRunCount()));
        session.setFinishedRunCount(defaultInt(stats.getFinishedRunCount()));
        session.setFailedRunCount(defaultInt(stats.getFailedRunCount()));
        if (RunLedgerStats.isFailedStatus(status)) {
            session.setStatus(SessionLedgerStatus.FAILED.name());
        } else {
            session.setStatus(SessionLedgerStatus.COMPLETED.name());
        }
        if (exists) {
            sessionLedgerRepository.update(session);
        } else {
            sessionLedgerRepository.save(session);
        }
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** Run 输入是不可被完成态覆盖的一等事实；ext.queryText 仅兼容升级前的旧记录。 */
    private String queryText(AgentContext context) {
        if (context == null || StringUtils.isBlank(context.getQuery())) {
            return null;
        }
        return context.getQuery();
    }

    private String effectiveQueryText(RunLedgerEntity run) {
        if (run == null) {
            return null;
        }
        if (StringUtils.isNotBlank(run.getQueryText())) {
            return run.getQueryText();
        }
        Map<String, Object> ext = run.getExt();
        Object legacyQuery = ext == null ? null : ext.get("queryText");
        String value = legacyQuery == null ? null : String.valueOf(legacyQuery);
        return StringUtils.isBlank(value) ? null : value;
    }

    private void ensureRunExists(String runId, String sessionId, String requestId, String traceId, String agentId, String triggerSource) {
        if (StringUtils.isBlank(runId) || runLedgerRepository.findByRunId(runId).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        log.warn("ledger implicit run create, agentId={}, triggerSource={}, sessionId={}, runId={}, requestId={}, traceId={}",
                agentId, triggerSource, sessionId, runId, requestId, traceId);
        runLedgerRepository.save(RunLedgerEntity.builder()
                .runId(runId)
                .sessionId(sessionId)
                .requestId(requestId)
                .traceId(traceId)
                .agentId(agentId)
                .triggerSource(triggerSource)
                .status("RECORDED")
                .startedAt(now)
                .idempotencyKey(String.join(":", "run", "implicit", defaultString(runId)))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}
