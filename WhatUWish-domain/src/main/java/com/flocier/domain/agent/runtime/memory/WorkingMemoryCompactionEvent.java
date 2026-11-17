package com.flocier.domain.agent.runtime.memory;

import lombok.Data;

/** 工作记忆压缩审计记录；记录策略、前后规模及受限快照，不参与默认上下文加载。 */
@Data
public class WorkingMemoryCompactionEvent {
    private Long id;
    private String sessionId;
    private String triggerRequestId;
    /** 新压缩投影轮次的请求标识；失败时为空。 */
    private String compactRequestId;
    private String strategy;
    private Integer status;
    /** 含 SYSTEM 与工具描述的压缩前估算；与仅估算消息的 afterTokens 口径不同。 */
    private Integer beforeTokens;
    private Integer afterTokens;
    private Integer beforeMessageCount;
    private Integer afterMessageCount;
    private Integer thresholdTokens;
    private String summaryText;
    private String beforeMessagesJson;
    private String afterMessagesJson;
    private String errorMessage;
}
