package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次完整执行的聚合视图。
 * 提供给回放、记忆提取和管理端查询使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerRunView {

    /** 所属会话账本头信息。 */
    private SessionLedgerEntity session;
    /** 执行主记录。 */
    private RunLedgerEntity run;
    /** 本次执行中的 LLM 调用列表。 */
    @Builder.Default
    private List<LlmInvocationLedgerEntity> llmInvocations = new ArrayList<>();
    /** 本次执行中的 Tool 调用列表。 */
    @Builder.Default
    private List<ToolInvocationLedgerEntity> toolInvocations = new ArrayList<>();
    /** 本次执行中的产物列表。 */
    @Builder.Default
    private List<ArtifactLedgerEntity> artifacts = new ArrayList<>();
}
