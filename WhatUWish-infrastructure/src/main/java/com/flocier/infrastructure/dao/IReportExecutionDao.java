package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.ReportExecutionInputArtifactPO;
import com.flocier.infrastructure.dao.po.ReportExecutionPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IReportExecutionDao {
    int insert(ReportExecutionPO po);

    int insertInputs(List<ReportExecutionInputArtifactPO> inputs);

    int update(ReportExecutionPO po);

    ReportExecutionPO queryByToolInvocationId(String toolInvocationId);

    List<ReportExecutionInputArtifactPO> queryInputsByReportExecutionId(String reportExecutionId);
}
