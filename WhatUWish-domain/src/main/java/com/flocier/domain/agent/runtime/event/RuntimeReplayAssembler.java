package com.flocier.domain.agent.runtime.event;

import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.service.replay.rich.RichRuntimeReplayProjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 将通用回放与 Rich Tool 过程事实合并成 Run 内确定性时间线。 */
@Component
public class RuntimeReplayAssembler {

    public RuntimeReplayProjection assemble(
            RunLedgerEntity run,
            RuntimeReplayProjection base,
            RichRuntimeReplayProjection rich
    ) {
        Map<String, List<RuntimeEvent>> progressByInvocation = groupByInvocation(rich.getEvents());
        List<RuntimeEvent> merged = new ArrayList<>();
        List<RuntimeEvent> terminal = new ArrayList<>();
        Set<String> attachedInvocations = new HashSet<>();

        for (RuntimeEvent event : base.getEvents()) {
            if (event.isTerminal()) {
                terminal.add(event);
                continue;
            }
            if (event.getEventType() == RuntimeEventType.TOOL_RESULT
                    && event.getPayload() instanceof ToolResultPayload payload) {
                List<RuntimeEvent> progress = progressByInvocation.getOrDefault(payload.getToolInvocationId(), List.of());
                merged.addAll(progress);
                if (!progress.isEmpty()) {
                    attachedInvocations.add(payload.getToolInvocationId());
                }
            }
            merged.add(event);
        }

        boolean incomplete = base.isDataIncomplete() || rich.isDataIncomplete();
        for (Map.Entry<String, List<RuntimeEvent>> entry : progressByInvocation.entrySet()) {
            if (!attachedInvocations.contains(entry.getKey())) {
                merged.addAll(entry.getValue());
                incomplete = true;
            }
        }
        merged.addAll(terminal);
        return new RuntimeReplayProjection(resequence(merged), incomplete);
    }

    private Map<String, List<RuntimeEvent>> groupByInvocation(List<RuntimeEvent> richEvents) {
        Map<String, List<RuntimeEvent>> result = new HashMap<>();
        for (RuntimeEvent event : richEvents) {
            ToolProgressPayload payload = (ToolProgressPayload) event.getPayload();
            result.computeIfAbsent(payload.getToolInvocationId(), ignored -> new ArrayList<>()).add(event);
        }
        result.values().forEach(items -> items.sort(Comparator
                .comparing((RuntimeEvent event) -> ((ToolProgressPayload) event.getPayload()).getToolSequence())
                .thenComparing(RuntimeEvent::getEventId)));
        return result;
    }

    private List<RuntimeEvent> resequence(List<RuntimeEvent> events) {
        List<RuntimeEvent> result = new ArrayList<>();
        long sequence = 0;
        for (RuntimeEvent event : events) {
            result.add(new RuntimeEvent(
                    event.getSchemaVersion(),
                    event.getEventId(),
                    ++sequence,
                    event.getEventType(),
                    event.getOccurredAt(),
                    event.getSessionId(),
                    event.getRunId(),
                    event.getRequestId(),
                    event.getAgentId(),
                    event.getPayload()
            ));
        }
        return List.copyOf(result);
    }
}
