package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.ImageGenerationExecutionInputArtifactPO;
import com.flocier.infrastructure.dao.po.ImageGenerationExecutionPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IImageGenerationExecutionDao {
    int insert(ImageGenerationExecutionPO po);
    int insertInputs(List<ImageGenerationExecutionInputArtifactPO> inputs);
    int update(ImageGenerationExecutionPO po);
    ImageGenerationExecutionPO queryByToolInvocationId(String toolInvocationId);
    List<ImageGenerationExecutionPO> queryPageByExecutionSourceAndSession(
            @Param("executionSource") String executionSource,
            @Param("sessionId") String sessionId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );
    long countByExecutionSourceAndSession(
            @Param("executionSource") String executionSource,
            @Param("sessionId") String sessionId
    );
    List<ImageGenerationExecutionInputArtifactPO> queryInputsByImageGenerationExecutionId(String imageGenerationExecutionId);
}
