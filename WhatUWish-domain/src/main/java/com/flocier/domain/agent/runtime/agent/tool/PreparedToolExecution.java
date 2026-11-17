package com.flocier.domain.agent.runtime.agent.tool;

import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;

import java.time.Instant;

/**
 * 单次工具调用在进入异步执行前已固定的运行时身份。
 *
 * <p>它不承载业务状态；预备失败时只携带一个受控结果，调用方无需再尝试执行工具。</p>
 */
public final class PreparedToolExecution {

    private final ToolInvokeRequest request;
    private final ToolCollection compatibilityCollection;
    private final BaseTool tool;
    private final ToolInvocationLedgerEntity invocation;
    private final Instant startedAt;
    private final ToolExecutionResult preparationFailure;

    private PreparedToolExecution(ToolInvokeRequest request,
                                  ToolCollection compatibilityCollection,
                                  BaseTool tool,
                                  ToolInvocationLedgerEntity invocation,
                                  Instant startedAt,
                                  ToolExecutionResult preparationFailure) {
        this.request = request;
        this.compatibilityCollection = compatibilityCollection;
        this.tool = tool;
        this.invocation = invocation;
        this.startedAt = startedAt;
        this.preparationFailure = preparationFailure;
    }

    public static PreparedToolExecution ready(ToolInvokeRequest request,
                                              BaseTool tool,
                                              ToolInvocationLedgerEntity invocation,
                                              Instant startedAt) {
        return new PreparedToolExecution(request, null, tool, invocation, startedAt, null);
    }

    /** 保留 ToolRunner 旧函数式调用方的兼容路径。 */
    public static PreparedToolExecution legacy(ToolCollection toolCollection,
                                               ToolInvokeRequest request,
                                               Instant startedAt) {
        return new PreparedToolExecution(request, toolCollection, null, null, startedAt, null);
    }

    public static PreparedToolExecution failed(ToolInvokeRequest request,
                                               Instant startedAt,
                                               ToolExecutionResult result) {
        return new PreparedToolExecution(request, null, null, null, startedAt, result);
    }

    public ToolInvokeRequest getRequest() { return request; }
    public ToolCollection getCompatibilityCollection() { return compatibilityCollection; }
    public BaseTool getTool() { return tool; }
    public ToolInvocationLedgerEntity getInvocation() { return invocation; }
    public Instant getStartedAt() { return startedAt; }
    public ToolExecutionResult getPreparationFailure() { return preparationFailure; }
    public boolean isReady() { return (tool != null || compatibilityCollection != null) && preparationFailure == null; }
    public boolean isLegacy() { return compatibilityCollection != null; }
}
