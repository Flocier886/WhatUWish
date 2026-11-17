package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Session 维度查询条件对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerSessionQueryEntity {
    /** 指定 sessionId 查询。 */
    private String sessionId;
    /** 返回数量限制。 */
    private Integer limit;
}
