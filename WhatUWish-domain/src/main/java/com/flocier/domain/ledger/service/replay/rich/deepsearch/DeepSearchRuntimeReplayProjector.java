package com.flocier.domain.ledger.service.replay.rich.deepsearch;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch.DeepSearchPresentationMapper;
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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 仅将已提交的 `deep_search_stage` 事实恢复为 DeepSearch Progress。 */
@Component
@RequiredArgsConstructor
public class DeepSearchRuntimeReplayProjector implements RichRuntimeReplayProjector {
    private static final String EVENT_TYPE = "deep_search_stage";
    private static final String TOOL_NAME = "deep_search_tool";
    private final IRuntimeEventLedgerRepository runtimeEventLedgerRepository;
    private final DeepSearchPresentationMapper presentationMapper;

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
            if (StringUtils.isBlank(fact.getRuntimeEventId()) || StringUtils.isBlank(fact.getToolInvocationId())) {
                incomplete = true;
                continue;
            }
            try {
                events.add(toEvent(run, fact));
            } catch (RuntimeException ignored) {
                events.add(unavailableEvent(run, fact));
                incomplete = true;
            }
        }
        return new RichRuntimeReplayProjection(List.copyOf(events), incomplete);
    }

    @SuppressWarnings("unchecked")
    private RuntimeEvent toEvent(RunLedgerEntity run, RuntimeEventLedgerEntity fact) {
        Map<String, Object> data = JSON.parseObject(fact.getPayloadJson(), Map.class);
        DeepSearchPresentationMapper.DeepSearchPresentation presentation = presentationMapper.restore(
                fact.getPresentationKind(), fact.getPayloadSchemaVersion(), data == null ? Map.of() : data);
        return event(run, fact, presentation.kind(), presentation.schemaVersion(), presentation.data());
    }

    private RuntimeEvent unavailableEvent(RunLedgerEntity run, RuntimeEventLedgerEntity fact) {
        return event(run, fact, "deep_search.presentation_unavailable.v1", "1",
                Map.of("reason", "历史过程详情不符合当前展示协议"));
    }

    private RuntimeEvent event(RunLedgerEntity run, RuntimeEventLedgerEntity fact, String kind, String schemaVersion,
                               Map<String, Object> data) {
        ToolProgressPayload payload = new ToolProgressPayload(
                fact.getRuntimeEventId(),
                fact.getToolInvocationId(),
                TOOL_NAME,
                fact.getSequenceNo(),
                fact.getStage(),
                fact.getStatus(),
                StringUtils.defaultIfBlank(fact.getMessage(), "DeepSearch 过程已记录"),
                fact.getArtifactId(),
                kind,
                schemaVersion,
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

    private Instant occurredAt(RuntimeEventLedgerEntity fact) {
        if (fact.getOccurredAt() != null) {
            return fact.getOccurredAt();
        }
        return fact.getRecordedAt() == null ? Instant.EPOCH : fact.getRecordedAt();
    }
}
