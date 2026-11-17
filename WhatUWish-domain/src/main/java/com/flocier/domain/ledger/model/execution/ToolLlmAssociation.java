package com.flocier.domain.ledger.model.execution;

/** 工具调用与父 LLM cycle 的事实关联状态。 */
public enum ToolLlmAssociation {
    /** 新链路已直接持久化父 LLM 调用标识。 */
    PERSISTED,
    /** 历史完整响应 payload 中的 toolCallId 可唯一证明该关系。 */
    INFERRED,
    /** 没有可靠父调用证据，保留为孤儿工具事实。 */
    ORPHAN
}
