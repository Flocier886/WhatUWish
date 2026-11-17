package com.flocier.domain.ledger.service.rich;

import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/** 已完成工具协议校验的 Rich Tool 稳定过程事实。 */
@Value
@Builder
public class RichRuntimeProgressCommand {
    ToolInvocationLedgerEntity invocation;
    String agentId;
    String upstreamEventId;
    Integer sequenceNo;
    String eventType;
    String stage;
    String status;
    Instant occurredAt;
    String message;
    String presentationKind;
    String presentationSchemaVersion;
    Map<String, Object> presentationData;
    String artifactId;
    String errorCode;
    String errorMessage;
}
