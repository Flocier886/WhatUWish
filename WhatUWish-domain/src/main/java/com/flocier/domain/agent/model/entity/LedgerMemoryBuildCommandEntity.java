package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 构建 Ledger Memory 视图的请求参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerMemoryBuildCommandEntity {

    /** 当前会话标识。 */
    private String sessionId;
    /** 当前 run 标识，可用于排除本次执行自身。 */
    private String currentRunId;
    /** 当前请求标识，可用于日志与排除当前请求。 */
    private String currentRequestId;
}
