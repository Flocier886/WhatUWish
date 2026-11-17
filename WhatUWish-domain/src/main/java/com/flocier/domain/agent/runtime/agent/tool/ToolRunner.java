package com.flocier.domain.agent.runtime.agent.tool;

import com.flocier.domain.agent.runtime.agent.AgentContext;

public interface ToolRunner {

    ToolExecutionResult run(ToolCollection toolCollection, ToolInvokeRequest request, AgentContext context);

    default PreparedToolExecution prepare(ToolCollection toolCollection, ToolInvokeRequest request, AgentContext context) {
        return PreparedToolExecution.legacy(toolCollection, request, java.time.Instant.now());
    }

    default ToolExecutionResult execute(PreparedToolExecution prepared, AgentContext context) {
        if (prepared == null || !prepared.isLegacy()) {
            throw new IllegalArgumentException("Prepared execution does not belong to the legacy ToolRunner path");
        }
        return run(prepared.getCompatibilityCollection(), prepared.getRequest(), context);
    }

}
