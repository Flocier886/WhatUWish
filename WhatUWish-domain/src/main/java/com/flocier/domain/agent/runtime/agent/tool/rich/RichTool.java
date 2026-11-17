package com.flocier.domain.agent.runtime.agent.tool.rich;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;

import java.time.Instant;

/**
 * Rich Tool 的专属执行契约。
 *
 * <p>这类工具拥有专属长任务、过程事实和终态语义。Runner 只统一预备 Invocation，
 * 不将 Rich Tool 纳入普通工具的业务收尾路径；它仍是 BaseTool，因此可被 ReAct 发现。</p>
 */
public interface RichTool extends BaseTool {

    String richToolType();

    ToolExecutionResult executeRich(ToolInvokeRequest request,
                                    AgentContext context,
                                    ToolInvocationLedgerEntity invocation,
                                    Instant startedAt);
}
