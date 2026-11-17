package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Rich Tool 追加式运行事实，不承载具体工具的领域状态。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeEventLedgerEntity {
    private String runtimeEventId;
    private String runId;
    private String sessionId;
    private String requestId;
    private String toolInvocationId;
    private Integer sequenceNo;
    private String upstreamEventId;
    private String eventType;
    private String stage;
    private String status;
    private Instant occurredAt;
    private Instant recordedAt;
    private String message;
    private String presentationKind;
    private String payloadSchemaVersion;
    private String payloadJson;
    private String artifactId;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
