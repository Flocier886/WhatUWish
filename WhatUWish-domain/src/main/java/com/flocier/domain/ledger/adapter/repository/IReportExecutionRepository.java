package com.flocier.domain.ledger.adapter.repository;

import com.flocier.domain.ledger.model.entity.ReportExecutionEntity;
import com.flocier.domain.ledger.model.entity.ReportExecutionInputArtifactEntity;

import java.util.List;
import java.util.Optional;

/** Report 专属状态投影及输入产物关系的持久化边界。 */
public interface IReportExecutionRepository {

    void save(ReportExecutionEntity entity);

    void saveInputArtifacts(List<ReportExecutionInputArtifactEntity> inputs);

    void update(ReportExecutionEntity entity);

    Optional<ReportExecutionEntity> findByToolInvocationId(String toolInvocationId);

    List<ReportExecutionInputArtifactEntity> findInputsByReportExecutionId(String reportExecutionId);
}
