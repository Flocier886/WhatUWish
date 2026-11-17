package com.flocier.domain.agent.runtime.agent;

import com.flocier.domain.agent.runtime.printer.Printer;

public abstract class AbstractAgent implements Agent {

    protected void emit(AgentContext context, String eventType, Object payload) {
        if (AgentRunMode.isReview(context)) return;
        Printer printer = context.getPrinter();
        if (printer != null) {
            printer.send(eventType, payload);
        }
    }
}
