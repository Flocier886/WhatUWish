package com.flocier.domain.ledger.service.replay.rich.imagegeneration;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.event.RuntimeEvent;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.event.ToolProgressPayload;
import com.flocier.domain.ledger.adapter.repository.IRuntimeEventLedgerRepository;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.domain.ledger.service.replay.rich.RichRuntimeReplayProjection;
import com.flocier.domain.ledger.service.replay.rich.RichRuntimeReplayProjector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 仅从已提交的 {@code image_generation_stage} 事实恢复图片生成稳定过程。 */
@Component
@RequiredArgsConstructor
public class ImageGenerationRuntimeReplayProjector implements RichRuntimeReplayProjector {

    private static final String EVENT_TYPE = "image_generation_stage";
    private static final String TOOL_NAME = "image_generation_tool";
    private static final String PRESENTATION_KIND = "image.progress.v1";
    private static final String PRESENTATION_VERSION = "1";
    private final IRuntimeEventLedgerRepository runtimeEventLedgerRepository;

    @Override
    public RichRuntimeReplayProjection project(RunLedgerEntity run) {
        if (run == null || StringUtils.isBlank(run.getRunId())) return new RichRuntimeReplayProjection(List.of(), true);
        List<RuntimeEvent> events = new ArrayList<>();
        boolean incomplete = false;
        for (RuntimeEventLedgerEntity fact : runtimeEventLedgerRepository.findByRunIdOrderByInvocationAndSequenceNo(run.getRunId())) {
            if (fact == null || !EVENT_TYPE.equals(fact.getEventType())) continue;
            if (StringUtils.isAnyBlank(fact.getRuntimeEventId(), fact.getToolInvocationId())
                    || fact.getSequenceNo() == null || fact.getSequenceNo() < 1) {
                incomplete = true;
                continue;
            }
            try {
                events.add(toEvent(run, fact));
            } catch (RuntimeException exception) {
                events.add(unavailableEvent(run, fact));
                incomplete = true;
            }
        }
        return new RichRuntimeReplayProjection(List.copyOf(events), incomplete);
    }

    @SuppressWarnings("unchecked")
    private RuntimeEvent toEvent(RunLedgerEntity run, RuntimeEventLedgerEntity fact) {
        if (!PRESENTATION_KIND.equals(fact.getPresentationKind()) || !PRESENTATION_VERSION.equals(fact.getPayloadSchemaVersion())) {
            throw new IllegalArgumentException("图片生成历史过程展示协议版本不匹配");
        }
        Map<String, Object> payload = JSON.parseObject(fact.getPayloadJson(), Map.class);
        return event(run, fact, controlledData(payload));
    }

    private RuntimeEvent unavailableEvent(RunLedgerEntity run, RuntimeEventLedgerEntity fact) {
        return event(run, fact, Map.of("reason", "历史图片生成过程详情不符合当前展示协议"));
    }

    private RuntimeEvent event(RunLedgerEntity run, RuntimeEventLedgerEntity fact, Map<String, Object> data) {
        ToolProgressPayload payload = new ToolProgressPayload(
                fact.getRuntimeEventId(), fact.getToolInvocationId(), TOOL_NAME, fact.getSequenceNo(), fact.getStage(),
                fact.getStatus(), StringUtils.defaultIfBlank(fact.getMessage(), "图片生成过程已记录"), fact.getArtifactId(),
                PRESENTATION_KIND, PRESENTATION_VERSION, data
        );
        return new RuntimeEvent(
                RuntimeEvent.SCHEMA_VERSION_V1, fact.getRuntimeEventId(), 1, RuntimeEventType.TOOL_PROGRESS, occurredAt(fact),
                run.getSessionId(), run.getRunId(), run.getRequestId(), run.getAgentId(), payload
        );
    }

    /** 回放再次做字段白名单，禁止历史 JSON 中的 Base64、URL、prompt 或 locator 恢复到前端。 */
    private Map<String, Object> controlledData(Map<String, Object> source) {
        if (source == null) return Map.of();
        Map<String, Object> data = new LinkedHashMap<>();
        copyText(source, data, "phase", 80); copyText(source, data, "status", 80); copyText(source, data, "mode", 32);
        copyText(source, data, "size", 32); copyText(source, data, "fileName", 255); copyText(source, data, "artifactId", 120);
        copyNumber(source, data, "referenceCount"); copyNumber(source, data, "maskCount");
        copyNumber(source, data, "requestedCount"); copyNumber(source, data, "generatedCount"); copyNumber(source, data, "outputIndex");
        if (source.get("usedFallback") instanceof Boolean value) data.put("usedFallback", value);
        return data;
    }

    private void copyText(Map<String, Object> source, Map<String, Object> target, String key, int maxLength) {
        Object value = source.get(key);
        if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
            String text = String.valueOf(value);
            target.put(key, text.length() <= maxLength ? text : text.substring(0, maxLength));
        }
    }

    private void copyNumber(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof Number number && number.intValue() >= 0) target.put(key, number.intValue());
    }

    private Instant occurredAt(RuntimeEventLedgerEntity fact) {
        return fact.getOccurredAt() != null ? fact.getOccurredAt()
                : fact.getRecordedAt() == null ? Instant.EPOCH : fact.getRecordedAt();
    }
}
