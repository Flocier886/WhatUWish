package com.flocier.domain.ledger.service.replay.rich;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.event.RuntimeEvent;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.event.ToolProgressPayload;
import com.flocier.domain.ledger.adapter.repository.IRuntimeEventLedgerRepository;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从已提交的 3B 步骤事实重建与 Live 相同的展示合同。 */
@Component
@RequiredArgsConstructor
public class CodeInterpreterRuntimeReplayProjector implements RichRuntimeReplayProjector {
    private final IRuntimeEventLedgerRepository facts;

    @Override
    @SuppressWarnings("unchecked")
    public RichRuntimeReplayProjection project(RunLedgerEntity run) {
        if (run == null || run.getRunId() == null) return new RichRuntimeReplayProjection(List.of(), true);
        List<RuntimeEvent> events = new ArrayList<>();
        boolean incomplete = false;
        for (RuntimeEventLedgerEntity fact : facts.findByRunIdOrderByInvocationAndSequenceNo(run.getRunId())) {
            if (!"code_interpreter_stage".equals(fact.getEventType())) continue;
            try {
                if (!"code_interpreter.step.v1".equals(fact.getPresentationKind())
                        || !"1".equals(fact.getPayloadSchemaVersion())
                        || fact.getSequenceNo() == null || fact.getSequenceNo() < 1)
                    throw new IllegalArgumentException("代码分析过程协议不匹配");
                Map<String, Object> original = JSON.parseObject(fact.getPayloadJson(), Map.class);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("step", original.getOrDefault("step", 0));
                for (String key : List.of("thought", "code", "observation", "status")) {
                    String value = String.valueOf(original.getOrDefault(key, ""));
                    data.put(key, value.substring(0, Math.min(value.length(), "code".equals(key) ? 12000 : 4000)));
                }
                ToolProgressPayload payload = new ToolProgressPayload(fact.getRuntimeEventId(),
                        fact.getToolInvocationId(), "code_interpreter", fact.getSequenceNo(), fact.getStage(),
                        fact.getStatus(), fact.getMessage(), fact.getArtifactId(), fact.getPresentationKind(),
                        fact.getPayloadSchemaVersion(), data);
                events.add(new RuntimeEvent(RuntimeEvent.SCHEMA_VERSION_V1, fact.getRuntimeEventId(), 1,
                        RuntimeEventType.TOOL_PROGRESS, fact.getOccurredAt(), run.getSessionId(), run.getRunId(),
                        run.getRequestId(), run.getAgentId(), payload));
            } catch (RuntimeException invalid) {
                incomplete = true;
            }
        }
        return new RichRuntimeReplayProjection(List.copyOf(events), incomplete);
    }
}
