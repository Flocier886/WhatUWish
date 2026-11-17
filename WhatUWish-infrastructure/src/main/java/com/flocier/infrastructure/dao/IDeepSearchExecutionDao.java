package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.DeepSearchExecutionPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IDeepSearchExecutionDao {
    int insert(DeepSearchExecutionPO po);
    int update(DeepSearchExecutionPO po);
    DeepSearchExecutionPO queryByToolInvocationId(String toolInvocationId);
}
