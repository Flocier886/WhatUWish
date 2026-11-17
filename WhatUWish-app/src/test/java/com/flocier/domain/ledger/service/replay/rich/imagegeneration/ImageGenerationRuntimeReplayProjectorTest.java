package com.flocier.domain.ledger.service.replay.rich.imagegeneration;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.event.ToolProgressPayload;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.RuntimeEventLedgerEntity;
import com.flocier.infrastructure.persistent.memory.InMemoryRuntimeEventLedgerRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Image 历史回放只恢复受控进度字段，拒绝存量 payload 中的敏感信息。 */
public class ImageGenerationRuntimeReplayProjectorTest {

    @Test
    public void shouldProjectOnlyWhitelistedImageStageFields() {
        InMemoryRuntimeEventLedgerRepository repository = new InMemoryRuntimeEventLedgerRepository();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", "artifact_created"); data.put("mode", "edits"); data.put("size", "1024x1024");
        data.put("artifactId", "image-1"); data.put("generatedCount", 1); data.put("usedFallback", true);
        data.put("prompt", "完整提示词不得回放"); data.put("storageKey", "private/image-1");
        data.put("upstreamUrl", "https://upstream.example/image"); data.put("base64", "never-store");
        repository.save(fact("fact-1", "image_generation_stage", "image.progress.v1", "1", data));
        repository.save(fact("other-tool", "report_stage", "report.progress.v1", "1", Map.of("content", "other")));

        var projection = new ImageGenerationRuntimeReplayProjector(repository).project(run());

        Assert.assertFalse(projection.isDataIncomplete());
        Assert.assertEquals(1, projection.getEvents().size());
        Assert.assertEquals(RuntimeEventType.TOOL_PROGRESS, projection.getEvents().get(0).getEventType());
        ToolProgressPayload payload = (ToolProgressPayload) projection.getEvents().get(0).getPayload();
        Assert.assertEquals("image.progress.v1", payload.getPresentationKind());
        Assert.assertEquals("image-1", payload.getPresentationData().get("artifactId"));
        String serialized = JSON.toJSONString(payload);
        Assert.assertFalse(serialized.contains("完整提示词不得回放"));
        Assert.assertFalse(serialized.contains("private/image-1"));
        Assert.assertFalse(serialized.contains("upstream.example"));
        Assert.assertFalse(serialized.contains("never-store"));
    }

    @Test
    public void shouldDegradeUnknownPresentationVersionWithoutRestoringPayload() {
        InMemoryRuntimeEventLedgerRepository repository = new InMemoryRuntimeEventLedgerRepository();
        repository.save(fact("fact-1", "image_generation_stage", "image.progress.v9", "9",
                Map.of("prompt", "不得泄露", "storageRef", "private/ref")));

        var projection = new ImageGenerationRuntimeReplayProjector(repository).project(run());

        Assert.assertTrue(projection.isDataIncomplete());
        ToolProgressPayload payload = (ToolProgressPayload) projection.getEvents().get(0).getPayload();
        Assert.assertEquals("历史图片生成过程详情不符合当前展示协议", payload.getPresentationData().get("reason"));
        String serialized = JSON.toJSONString(payload);
        Assert.assertFalse(serialized.contains("不得泄露"));
        Assert.assertFalse(serialized.contains("private/ref"));
    }

    private static RuntimeEventLedgerEntity fact(String eventId, String eventType, String presentationKind,
                                                  String schemaVersion, Map<String, Object> data) {
        return RuntimeEventLedgerEntity.builder().runtimeEventId(eventId).runId("run-1").sessionId("session-1")
                .requestId("request-1").toolInvocationId("tool-image-1").sequenceNo(1).eventType(eventType)
                .stage("generation_started").status("running").message("正在生成图片").presentationKind(presentationKind)
                .payloadSchemaVersion(schemaVersion).payloadJson(JSON.toJSONString(data))
                .occurredAt(Instant.parse("2026-08-29T10:00:00Z")).build();
    }

    private static RunLedgerEntity run() {
        return RunLedgerEntity.builder().runId("run-1").sessionId("session-1").requestId("request-1").agentId("agent-1").build();
    }
}
