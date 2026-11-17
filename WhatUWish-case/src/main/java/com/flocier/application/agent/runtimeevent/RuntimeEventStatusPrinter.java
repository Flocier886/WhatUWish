package com.flocier.application.agent.runtimeevent;

import com.flocier.domain.agent.runtime.event.RunStatusPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.printer.Printer;

import java.time.Instant;

/** legacy Printer 保持原输出，同时仅将状态类信息适配为新的 RUN_STATUS。 */
public class RuntimeEventStatusPrinter implements Printer {
    private final Printer legacyPrinter;
    private final RuntimeEventPublisher publisher;
    private final RuntimeEventScope scope;

    public RuntimeEventStatusPrinter(Printer legacyPrinter, RuntimeEventPublisher publisher, RuntimeEventScope scope) {
        this.legacyPrinter = legacyPrinter;
        this.publisher = publisher;
        this.scope = scope;
    }

    @Override
    public void send(String type, Object content) {
        legacyPrinter.send(type, content);
        if (!"status".equals(type) && !"step".equals(type)) return;
        publisher.publish(new RuntimeEventDraft(RuntimeEventType.RUN_STATUS, Instant.now(), scope.getSessionId(), scope.getRunId(),
                scope.getRequestId(), scope.getAgentId(), new RunStatusPayload("RUNNING", content == null ? null : String.valueOf(content))));
    }
}
