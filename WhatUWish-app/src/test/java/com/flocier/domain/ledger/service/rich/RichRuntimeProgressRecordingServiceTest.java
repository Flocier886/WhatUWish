package com.flocier.domain.ledger.service.rich;

import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.infrastructure.persistent.memory.InMemoryRuntimeEventLedgerRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** 通用 Rich Progress 记录的幂等、顺序和展示安全边界回归。 */
public class RichRuntimeProgressRecordingServiceTest {

    @Test
    public void shouldPersistOnlyControlledOrderedFactsAndPublishOnce() {
        InMemoryRuntimeEventLedgerRepository repository = new InMemoryRuntimeEventLedgerRepository();
        List<RuntimeEventDraft> published = new ArrayList<>();
        RichRuntimeProgressRecordingService service = new RichRuntimeProgressRecordingService(repository, published::add);

        Assert.assertFalse(service.record(command("event-1", 1, Map.of("stage", "search"))).isDuplicate());
        Assert.assertTrue(service.record(command("event-1", 1, Map.of("stage", "search"))).isDuplicate());
        Assert.assertEquals(1, repository.findByToolInvocationIdOrderBySequenceNo("tool-1").size());
        Assert.assertEquals(1, published.size());

        try {
            service.record(command("event-3", 3, Map.of("stage", "gap")));
            Assert.fail("sequence gap must be rejected");
        } catch (RichRuntimeProgressException exception) {
            Assert.assertEquals("rich_runtime_sequence_gap", exception.getErrorCode());
        }
        try {
            service.record(command("event-2", 2, Map.of("storageKey", "private")));
            Assert.fail("internal storage fields must be rejected");
        } catch (RichRuntimeProgressException exception) {
            Assert.assertEquals("rich_runtime_progress_invalid", exception.getErrorCode());
        }
        Assert.assertEquals(1, repository.findByToolInvocationIdOrderBySequenceNo("tool-1").size());
    }

    private RichRuntimeProgressCommand command(String upstreamEventId, int sequence, Map<String, Object> data) {
        return RichRuntimeProgressCommand.builder()
                .invocation(ToolInvocationLedgerEntity.builder().toolInvocationId("tool-1").toolCallId("call-1")
                        .toolName("deep_search_tool").runId("run-1").sessionId("session-1").requestId("request-1").build())
                .agentId("agent-1").upstreamEventId(upstreamEventId).sequenceNo(sequence).eventType("deep_search_stage")
                .stage("search_completed").status("completed").occurredAt(Instant.EPOCH).message("已完成")
                .presentationKind("deep_search.progress.v1").presentationSchemaVersion("1").presentationData(data).build();
    }
}
