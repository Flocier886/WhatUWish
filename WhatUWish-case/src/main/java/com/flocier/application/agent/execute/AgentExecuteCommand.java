package com.flocier.application.agent.execute;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecuteCommand {
    private String aiAgentId;
    private String message;
    private String sessionId;
    private Integer maxStep;
    private String legacyStrategy;
    /** 新 Runtime Event v1 入口预先分配的稳定关联标识；legacy 入口可为空。 */
    private String requestId;
    private String runId;
    /** 4D 恢复时用来替换原 RequestDesktopControl 的等待观察。 */
    private String resumeControlId;
    /** Controller 已核验授权、归属和状态的本轮不可变快照。 */
    private List<ConversationFileRecord> selectedFiles;
}
