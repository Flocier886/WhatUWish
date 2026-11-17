package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.DesktopControlPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IDesktopControlDao {
    int insert(DesktopControlPO row);
    DesktopControlPO selectByControlId(@Param("controlId") String controlId);
    DesktopControlPO selectByResumeRequestId(@Param("resumeRequestId") String resumeRequestId);
    List<DesktopControlPO> selectBySessionId(@Param("sessionId") String sessionId);
    int casCompletePending(@Param("controlId") String controlId,
                           @Param("resumeRequestId") String resumeRequestId,
                           @Param("resumeRunId") String resumeRunId);
    int casClaimResume(@Param("resumeRequestId") String resumeRequestId);
    int markCompleted(@Param("controlId") String controlId);
    int markFailed(@Param("controlId") String controlId);
    int casExpire(@Param("controlId") String controlId);
}
