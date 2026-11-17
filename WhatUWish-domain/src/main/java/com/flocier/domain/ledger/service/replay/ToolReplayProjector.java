package com.flocier.domain.ledger.service.replay;

import com.flocier.domain.ledger.model.execution.ExecutionToolInvocation;
import com.flocier.domain.ledger.model.replay.ToolReplayEntry;

/** rich tool 的回放扩展点；未注册工具由默认投影器处理。 */
public interface ToolReplayProjector {
    boolean supports(String toolName);

    ToolReplayEntry project(ExecutionToolInvocation tool, int sequence);
}
