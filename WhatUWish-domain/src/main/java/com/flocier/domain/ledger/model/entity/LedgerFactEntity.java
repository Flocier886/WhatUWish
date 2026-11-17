package com.flocier.domain.ledger.model.entity;

import com.flocier.domain.ledger.model.vo.FactType;
import com.flocier.domain.ledger.model.vo.LedgerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerFactEntity {

    private String factId;
    private FactType factType;
    private String subjectId;
    private String subjectType;
    private String sourceId;
    private String sourceType;
    private String bizId;
    private String requestId;
    private String sessionId;
    private String traceId;
    private String parentFactId;
    private Integer stepNo;
    private String providerCode;
    private String componentCode;
    private String inputSummary;
    private String outputSummary;
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();
    private String artifactRef;
    private String usageType;
    private Long usageQuantity;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String currency;
    private String pricingVersion;
    private LedgerStatus status;
    private String idempotencyKey;
    private Instant occurredAt;
    private Instant createdAt;
    private Instant updatedAt;
    @Builder.Default
    private Map<String, Object> ext = new LinkedHashMap<>();
}
