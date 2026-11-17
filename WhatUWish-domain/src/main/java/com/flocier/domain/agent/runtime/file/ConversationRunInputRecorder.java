package com.flocier.domain.agent.runtime.file;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.event.InputFilePayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 将已校验并物化的会话文件登记为本轮 Run 的 input Artifact 和 Live 输入事件。 */
@Service
@RequiredArgsConstructor
public class ConversationRunInputRecorder {
    private final LedgerRecordingService ledger;
    private final RuntimeEventPublisher publisher;

    /** 每个 runId/fileId 使用确定的幂等键；逐项保存输入顺序，但不复制或覆盖原上传记录。 */
    public void record(AgentContext context, List<ConversationFileRecord> files) {
        if (files == null || files.isEmpty()) return;
        String runId = context.getAttribute("runId");
        for (int index = 0; index < files.size(); index++) {
            ConversationFileRecord file = files.get(index);
            String key = "conversation-file:input:" + runId + ":" + file.fileId();
            String artifactId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
            Instant now = Instant.now();
            ArtifactLedgerEntity artifact = ArtifactLedgerEntity.builder()
                    .artifactId(artifactId).runId(runId).sessionId(context.getSessionId())
                    .requestId(context.getRequestId()).traceId(context.getTraceId())
                    .artifactRole("input").visibility("internal")
                    .sourceType("user_upload").sourceId(file.fileId()).sourceName("conversation_file")
                    .artifactType("file").artifactName(file.displayName()).fileName(file.displayName())
                    .storageType(file.storageType()).storageKey(file.storageKey())
                    .mimeType(file.mimeType()).size(file.fileSize()).fileSize(file.fileSize())
                    .fileHash(file.fileHash()).metadataJson("{\"inputOrder\":" + (index + 1) + "}")
                    .deleted(0).idempotencyKey(key).createdAt(now).updatedAt(now).build();
            ArtifactLedgerEntity recorded = ledger.recordArtifact(artifact);
            if (recorded == null) {
                throw new IllegalStateException("本轮输入文件登记失败: " + file.fileId());
            }
            publisher.publishAfterCommit(new RuntimeEventDraft(RuntimeEventType.INPUT_FILE, now,
                    context.getSessionId(), runId, context.getRequestId(), context.getAttribute("agentId"),
                    new InputFilePayload(file.fileId(), file.displayName(), file.mimeType(), file.fileSize(),
                            index + 1, "AVAILABLE")));
        }
    }
}
