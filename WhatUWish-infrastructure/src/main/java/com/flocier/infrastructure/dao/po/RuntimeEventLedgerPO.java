package com.flocier.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeEventLedgerPO {
    private Long id;
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
    private LocalDateTime occurredAt;
    private LocalDateTime recordedAt;
    private String message;
    private String presentationKind;
    private String payloadSchemaVersion;
    private String payloadJson;
    private String artifactId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
