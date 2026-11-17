package com.flocier.domain.ledger.service.replay.rich.report;

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

/** Report 历史回放只恢复稳定阶段，并对白名单之外的历史数据再次收口。 */
public class ReportRuntimeReplayProjectorTest {

    @Test
    public void shouldProjectOnlyReportStagesAndNeverRestoreContentDelta() {
        InMemoryRuntimeEventLedgerRepository repository = new InMemoryRuntimeEventLedgerRepository();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outputType", "html");
        data.put("fileName", "report.html");
        data.put("artifactId", "artifact-1");
        data.put("inputArtifactCount", 2);
        data.put("content", "报告全文不能出现在历史事件中");
        data.put("storageKey", "private/internal/key");
        repository.save(fact("fact-1", "report_stage", "report.progress.v1", "1", data));
        repository.save(fact("fact-delta", "report_content_delta", "report.content_delta.v1", "1",
                Map.of("content", "也不能恢复")));

        var projection = new ReportRuntimeReplayProjector(repository).project(run());

        Assert.assertFalse(projection.isDataIncomplete());
        Assert.assertEquals(1, projection.getEvents().size());
        Assert.assertEquals(RuntimeEventType.TOOL_PROGRESS, projection.getEvents().get(0).getEventType());
        ToolProgressPayload payload = (ToolProgressPayload) projection.getEvents().get(0).getPayload();
        Assert.assertEquals("report.progress.v1", payload.getPresentationKind());
        Assert.assertEquals("html", payload.getPresentationData().get("outputType"));
        String serialized = JSON.toJSONString(payload);
        Assert.assertFalse(serialized.contains("报告全文不能出现在历史事件中"));
        Assert.assertFalse(serialized.contains("private/internal/key"));
    }

    @Test
    public void shouldDegradeIncompatibleReportPresentationWithoutLeakingStoredPayload() {
        InMemoryRuntimeEventLedgerRepository repository = new InMemoryRuntimeEventLedgerRepository();
        repository.save(fact("fact-1", "report_stage", "report.progress.v9", "9",
                Map.of("content", "不兼容正文", "storageKey", "private/key")));

        var projection = new ReportRuntimeReplayProjector(repository).project(run());

        Assert.assertTrue(projection.isDataIncomplete());
        ToolProgressPayload payload = (ToolProgressPayload) projection.getEvents().get(0).getPayload();
        Assert.assertEquals("历史 Report 过程详情不符合当前展示协议", payload.getPresentationData().get("reason"));
        Assert.assertFalse(JSON.toJSONString(payload).contains("private/key"));
        Assert.assertFalse(JSON.toJSONString(payload).contains("不兼容正文"));
    }

    private RuntimeEventLedgerEntity fact(String eventId, String eventType, String presentationKind,
                                          String schemaVersion, Map<String, Object> data) {
        return RuntimeEventLedgerEntity.builder()
                .runtimeEventId(eventId)
                .runId("run-1")
                .sessionId("session-1")
                .requestId("request-1")
                .toolInvocationId("tool-report-1")
                .sequenceNo(1)
                .eventType(eventType)
                .stage("generation_started")
                .status("running")
                .message("正在生成报告")
                .presentationKind(presentationKind)
                .payloadSchemaVersion(schemaVersion)
                .payloadJson(JSON.toJSONString(data))
                .occurredAt(Instant.parse("2026-08-28T10:00:00Z"))
                .build();
    }

    private RunLedgerEntity run() {
        return RunLedgerEntity.builder()
                .runId("run-1")
                .sessionId("session-1")
                .requestId("request-1")
                .agentId("agent-1")
                .build();
    }
}
