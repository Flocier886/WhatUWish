package com.flocier.infrastructure.dao;

import com.flocier.domain.agent.runtime.memory.WorkingMemoryMessage;
import com.flocier.domain.agent.runtime.memory.WorkingMemorySearchMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 工作记忆消息表的 MyBatis 访问入口。 */
@Mapper
public interface IWorkingMemoryMessageDao {
    /** 按所属轮次和消息顺序恢复原生模型消息。 */
    List<WorkingMemoryMessage> selectByTurnIds(@Param("turnIds") List<Long> turnIds);
    WorkingMemoryMessage selectByTurnAndSeq(@Param("turnId") long turnId, @Param("seqNo") int seqNo);
    void insert(WorkingMemoryMessage message);

    /** 包含 INVALID 历史的主工作记忆检索。 */
    List<WorkingMemorySearchMessage> searchFullText(@Param("sessionId") String sessionId,
                                                     @Param("query") String query,
                                                     @Param("roles") List<String> roles,
                                                     @Param("limit") int limit);
    List<WorkingMemorySearchMessage> scanHistory(@Param("sessionId") String sessionId,
                                                  @Param("query") String query,
                                                  @Param("roles") List<String> roles,
                                                  @Param("limit") int limit);
    WorkingMemorySearchMessage selectScrollAnchor(@Param("sessionId") String sessionId,
                                                   @Param("messageId") long messageId);
    List<WorkingMemorySearchMessage> selectScrollBefore(@Param("sessionId") String sessionId,
                                                         @Param("messageId") long messageId,
                                                         @Param("roles") List<String> roles,
                                                         @Param("limit") int limit);
    List<WorkingMemorySearchMessage> selectScrollAfter(@Param("sessionId") String sessionId,
                                                        @Param("messageId") long messageId,
                                                        @Param("roles") List<String> roles,
                                                        @Param("limit") int limit);
    List<WorkingMemorySearchMessage> selectHistoryBySession(@Param("sessionId") String sessionId);
    List<Map<String, Object>> selectRecentSessions(@Param("limit") int limit);
    Map<String, Object> selectSessionSummary(@Param("sessionId") String sessionId);
}
