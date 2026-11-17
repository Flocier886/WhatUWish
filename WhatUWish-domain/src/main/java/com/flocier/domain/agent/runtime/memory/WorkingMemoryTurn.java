package com.flocier.domain.agent.runtime.memory;

import lombok.Data;

import java.time.LocalDateTime;

/** 一次成功 ReAct 增量或压缩后的完整工作记忆投影所对应的轮次。 */
@Data
public class WorkingMemoryTurn {
    /** 保留 master 的状态值；默认只读取 READY，INVALID 仍留库供历史查询。 */
    public static final int BUILDING = 0;
    public static final int READY = 1;
    public static final int INVALID = 2;

    private Long id;
    private String sessionId;
    private String memoryScope = "main";
    private String requestId;
    private String runId;
    /** 同一会话及作用域内的持久化顺序号。 */
    private Integer turnSeq;
    private String entryAgent;
    /** BUILDING、READY 或 INVALID；压缩事务会将旧 READY 改为 INVALID。 */
    private Integer status;
    private Integer schemaVersion = 1;
    private Integer messageCount;
    private Integer tokenEstimate;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
