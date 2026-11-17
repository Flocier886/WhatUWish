package com.flocier.domain.ledger.model.entity;

import com.flocier.domain.ledger.model.vo.FactType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerFactQueryEntity {
    private String sessionId;
    private String traceId;
    private String requestId;
    private String sourceType;
    private FactType factType;
    private Integer stepNo;
    private Instant occurredAfter;
    private Instant occurredBefore;
    private Integer limit;
}
