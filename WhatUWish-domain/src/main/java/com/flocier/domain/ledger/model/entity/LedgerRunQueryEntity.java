package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Run 维度的查询条件对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerRunQueryEntity {
    /** 指定 runId 查询。 */
    private String runId;
    /** 指定 sessionId 查询。 */
    private String sessionId;
    /** 指定 requestId 查询。 */
    private String requestId;
    /** 指定 traceId 查询。 */
    private String traceId;
    /** 返回数量限制。 */
    private Integer limit;
}
