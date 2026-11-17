package com.flocier.domain.ledger.model.execution;

import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 一个已落账 LLM 决策及其归属的工具调用。 */
@Value
@Builder
public class ExecutionLlmCycle {
    LlmInvocationLedgerEntity llmInvocation;
    List<ExecutionToolInvocation> toolInvocations;
}
