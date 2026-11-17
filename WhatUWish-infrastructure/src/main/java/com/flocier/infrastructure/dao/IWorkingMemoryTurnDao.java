package com.flocier.infrastructure.dao;

import com.flocier.domain.agent.runtime.memory.WorkingMemoryTurn;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 工作记忆轮次表的 MyBatis 访问入口。 */
@Mapper
public interface IWorkingMemoryTurnDao {
    /** 查询请求是否已写入，用于幂等判断。 */
    WorkingMemoryTurn selectByRequestId(@Param("requestId") String requestId);
    /** 按顺序读取会话内仍可注入的轮次。 */
    List<WorkingMemoryTurn> selectReady(@Param("sessionId") String sessionId);
    /** 读取可注入的 READY 和未完成 BUILDING 轮次。 */
    List<WorkingMemoryTurn> selectLoadable(@Param("sessionId") String sessionId);
    /** 取得当前最大轮次序号。 */
    Integer selectMaxSeq(@Param("sessionId") String sessionId);
    void insert(WorkingMemoryTurn turn);
    /** 压缩替换前失效旧 READY，保留其行供后续检索。 */
    void invalidateReady(@Param("sessionId") String sessionId);
    /** 仅失效事务中实际读取到的轮次，避免误伤并发创建的段。 */
    int invalidateLoadable(@Param("sessionId") String sessionId, @Param("turnIds") List<Long> turnIds);
    int advanceBuilding(@Param("turnId") long turnId, @Param("seqNo") int seqNo,
                        @Param("tokenEstimate") int tokenEstimate);
    int completeBuilding(@Param("segmentId") String segmentId);
}
