package com.flocier.infrastructure.dao;

import com.flocier.infrastructure.dao.po.ConversationFilePO;
import com.flocier.infrastructure.dao.po.ConversationFileSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/** 会话授权表与文件元数据表的 MyBatis 操作；字节内容仍由 Artifact Content Port 管理。 */
@Mapper
public interface IConversationFileDao {
    int insertSession(ConversationFileSessionPO session);
    ConversationFileSessionPO findSession(String sessionId);
    /** SQL 条件更新 active_file_count，保证并发上传时的文件数上限。 */
    int reserveFileSlot(@Param("sessionId") String sessionId, @Param("limit") int limit);
    int releaseFileSlot(String sessionId);
    int insertFile(ConversationFilePO file);
    ConversationFilePO findFile(String fileId);
    List<ConversationFilePO> listFiles(String sessionId);
    int changeStatus(@Param("fileId") String fileId, @Param("expectedStatus") String expectedStatus,
                     @Param("status") String status, @Param("removedAt") LocalDateTime removedAt);
}
