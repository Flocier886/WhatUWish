package com.flocier.domain.ledger;

import com.flocier.domain.ledger.model.entity.ReportExecutionEntity;
import com.flocier.domain.ledger.model.entity.ReportExecutionInputArtifactEntity;
import com.flocier.infrastructure.adapter.repository.MysqlReportExecutionRepository;
import com.flocier.infrastructure.dao.IReportExecutionDao;
import com.flocier.infrastructure.dao.po.ReportExecutionInputArtifactPO;
import com.flocier.infrastructure.dao.po.ReportExecutionPO;
import com.flocier.infrastructure.persistent.memory.InMemoryReportExecutionRepository;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Report 专属执行投影 Repository 的离线持久化契约。 */
public class ReportExecutionRepositoryTest {

    @Test
    public void shouldSaveReadSortInputsAndRejectConcurrentMemoryUpdate() {
        InMemoryReportExecutionRepository repository = new InMemoryReportExecutionRepository();
        ReportExecutionEntity started = execution(0L, "running");
        repository.save(started);
        repository.saveInputArtifacts(List.of(input("artifact-2", 1), input("artifact-1", 0)));

        ReportExecutionEntity loaded = repository.findByToolInvocationId("invocation-1").orElseThrow();
        loaded.setStatus("mutated-outside");

        Assert.assertEquals("running", repository.findByToolInvocationId("invocation-1").orElseThrow().getStatus());
        Assert.assertEquals(List.of("artifact-1", "artifact-2"), repository.findInputsByReportExecutionId("report-1")
                .stream().map(ReportExecutionInputArtifactEntity::getArtifactId).toList());

        ReportExecutionEntity completed = execution(1L, "completed");
        repository.update(completed);
        Assert.assertEquals("completed", repository.findByToolInvocationId("invocation-1").orElseThrow().getStatus());

        try {
            repository.update(execution(1L, "failed"));
            Assert.fail("stale update must be rejected");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("Report execution concurrent update conflict", exception.getMessage());
        }
    }

    @Test
    public void shouldRejectDuplicateInputArtifactAndInputOrder() {
        InMemoryReportExecutionRepository duplicateArtifact = new InMemoryReportExecutionRepository();
        try {
            duplicateArtifact.saveInputArtifacts(List.of(input("artifact-1", 0), input("artifact-1", 1)));
            Assert.fail("duplicate artifact must be rejected");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("Report execution input artifact duplicates", exception.getMessage());
        }

        InMemoryReportExecutionRepository duplicateOrder = new InMemoryReportExecutionRepository();
        try {
            duplicateOrder.saveInputArtifacts(List.of(input("artifact-1", 0), input("artifact-2", 0)));
            Assert.fail("duplicate input order must be rejected");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("Report execution input artifact order duplicates", exception.getMessage());
        }
    }

    @Test
    public void shouldMapMysqlProjectionAndTurnZeroRowUpdateIntoOptimisticLockConflict() throws Exception {
        InMemoryDao dao = new InMemoryDao();
        MysqlReportExecutionRepository repository = new MysqlReportExecutionRepository();
        Field field = MysqlReportExecutionRepository.class.getDeclaredField("dao");
        field.setAccessible(true);
        field.set(repository, dao);

        repository.save(execution(0L, "running"));
        repository.saveInputArtifacts(List.of(input("artifact-1", 0)));
        ReportExecutionEntity loaded = repository.findByToolInvocationId("invocation-1").orElseThrow();
        Assert.assertEquals("report-1", loaded.getReportExecutionId());
        Assert.assertEquals(Instant.parse("2026-08-28T00:00:00Z"), loaded.getStartedAt());
        Assert.assertEquals("artifact-1", repository.findInputsByReportExecutionId("report-1").get(0).getArtifactId());

        repository.update(execution(1L, "completed"));
        try {
            repository.update(execution(1L, "failed"));
            Assert.fail("MyBatis zero row update must become an optimistic lock conflict");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("Report execution concurrent update conflict", exception.getMessage());
        }
    }

    private static ReportExecutionEntity execution(long version, String status) {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        return ReportExecutionEntity.builder()
                .reportExecutionId("report-1")
                .toolInvocationId("invocation-1")
                .pythonExecutionId("python-1")
                .runId("run-1")
                .sessionId("session-1")
                .requestId("request-1")
                .taskSummary("受控任务摘要")
                .outputType("markdown")
                .outputFileName("report.md")
                .status(status)
                .inputArtifactCount(2)
                .startedAt(now)
                .finishedAt("running".equals(status) ? null : now)
                .versionNo(version)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static ReportExecutionInputArtifactEntity input(String artifactId, int order) {
        return ReportExecutionInputArtifactEntity.builder()
                .reportExecutionId("report-1")
                .artifactId(artifactId)
                .inputOrder(order)
                .createdAt(Instant.parse("2026-08-28T00:00:00Z"))
                .build();
    }

    private static class InMemoryDao implements IReportExecutionDao {
        private ReportExecutionPO execution;
        private final List<ReportExecutionInputArtifactPO> inputs = new ArrayList<>();

        @Override
        public int insert(ReportExecutionPO value) {
            execution = value;
            return 1;
        }

        @Override
        public int insertInputs(List<ReportExecutionInputArtifactPO> values) {
            inputs.addAll(values);
            return values.size();
        }

        @Override
        public int update(ReportExecutionPO value) {
            if (execution == null || value.getVersionNo() == null || execution.getVersionNo() == null
                    || value.getVersionNo() != execution.getVersionNo() + 1) {
                return 0;
            }
            execution = value;
            return 1;
        }

        @Override
        public ReportExecutionPO queryByToolInvocationId(String toolInvocationId) {
            return execution != null && execution.getToolInvocationId().equals(toolInvocationId) ? execution : null;
        }

        @Override
        public List<ReportExecutionInputArtifactPO> queryInputsByReportExecutionId(String reportExecutionId) {
            return inputs.stream().filter(value -> reportExecutionId.equals(value.getReportExecutionId())).toList();
        }
    }
}
