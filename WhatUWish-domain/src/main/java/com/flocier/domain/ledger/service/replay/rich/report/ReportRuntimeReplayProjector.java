package com.flocier.domain.ledger.service.replay.rich.report;

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

/** 仅从已提交的 {@code report_stage} 事实重建 Report 稳定过程；正文 delta 从不进入历史回放。 */
@Component
@RequiredArgsConstructor
public class ReportRuntimeReplayProjector implements RichRuntimeReplayProjector {

    private static final String EVENT_TYPE = "report_stage";
    private static final String TOOL_NAME = "report_tool";
    private static final String PRESENTATION_KIND = "report.progress.v1";
    private static final String PRESENTATION_VERSION = "1";

    private final IRuntimeEventLedgerRepository runtimeEventLedgerRepository;

    @Override
    public RichRuntimeReplayProjection project(RunLedgerEntity run) {
        if (run == null || StringUtils.isBlank(run.getRunId())) {
            return new RichRuntimeReplayProjection(List.of(), true);
        }
        List<RuntimeEvent> events = new ArrayList<>();
        boolean incomplete = false;
        List<RuntimeEventLedgerEntity> facts = runtimeEventLedgerRepository
                .findByRunIdOrderByInvocationAndSequenceNo(run.getRunId());
        for (RuntimeEventLedgerEntity fact : facts) {
            if (fact == null || !EVENT_TYPE.equals(fact.getEventType())) {
                continue;
            }
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
        if (!PRESENTATION_KIND.equals(fact.getPresentationKind())
                || !PRESENTATION_VERSION.equals(fact.getPayloadSchemaVersion())) {
            throw new IllegalArgumentException("Report 历史过程展示协议版本不匹配");
        }
        Map<String, Object> payload = JSON.parseObject(fact.getPayloadJson(), Map.class);
        return event(run, fact, controlledData(payload));
    }

    private RuntimeEvent unavailableEvent(RunLedgerEntity run, RuntimeEventLedgerEntity fact) {
        return event(run, fact, Map.of("reason", "历史 Report 过程详情不符合当前展示协议"));
    }

    private RuntimeEvent event(RunLedgerEntity run, RuntimeEventLedgerEntity fact, Map<String, Object> data) {
        ToolProgressPayload payload = new ToolProgressPayload(
                fact.getRuntimeEventId(),
                fact.getToolInvocationId(),
                TOOL_NAME,
                fact.getSequenceNo(),
                fact.getStage(),
                fact.getStatus(),
                StringUtils.defaultIfBlank(fact.getMessage(), "Report 过程已记录"),
                fact.getArtifactId(),
                PRESENTATION_KIND,
                PRESENTATION_VERSION,
                data
        );
        return new RuntimeEvent(
                RuntimeEvent.SCHEMA_VERSION_V1,
                fact.getRuntimeEventId(),
                1,
                RuntimeEventType.TOOL_PROGRESS,
                occurredAt(fact),
                run.getSessionId(),
                run.getRunId(),
                run.getRequestId(),
                run.getAgentId(),
                payload
        );
    }

    /** 回放再次执行字段白名单，避免历史意外脏数据变成用户侧动态 JSON。 */
    private Map<String, Object> controlledData(Map<String, Object> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        copyText(source, data, "phase", 80);
        copyText(source, data, "status", 80);
        copyText(source, data, "outputType", 40);
        copyText(source, data, "fileName", 255);
        copyText(source, data, "artifactId", 120);
        copyNumber(source, data, "inputArtifactCount");
        copyNumber(source, data, "acceptedArtifactCount");
        return data;
    }

    private void copyText(Map<String, Object> source, Map<String, Object> target, String key, int limit) {
        Object value = source.get(key);
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (StringUtils.isNotBlank(text)) {
            target.put(key, text.length() <= limit ? text : text.substring(0, limit));
        }
    }

    private void copyNumber(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof Number number && number.intValue() >= 0) {
            target.put(key, number.intValue());
        }
    }

    private Instant occurredAt(RuntimeEventLedgerEntity fact) {
        if (fact.getOccurredAt() != null) {
            return fact.getOccurredAt();
        }
        return fact.getRecordedAt() == null ? Instant.EPOCH : fact.getRecordedAt();
    }
}
