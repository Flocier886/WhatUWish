package com.flocier.domain.agent.runtime.agent.tool.rich;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.AgentRunMode;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.DefaultToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.PreparedToolExecution;
import com.flocier.domain.agent.runtime.agent.tool.ToolRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 工具执行入口只负责在 Rich Tool 与普通 Tool Runner 之间分流。
 * Rich Tool 的流协议、账本生命周期和业务 observation 都留在其专属实现内。
 */
@Primary
@Component
@RequiredArgsConstructor
public class RichToolRunner implements ToolRunner {

    private final DefaultToolRunner defaultToolRunner;

    @Override
    public ToolExecutionResult run(ToolCollection toolCollection, ToolInvokeRequest request, AgentContext context) {
        return execute(prepare(toolCollection, request, context), context);
    }

    @Override
    public PreparedToolExecution prepare(ToolCollection toolCollection, ToolInvokeRequest request, AgentContext context) {
        if (AgentRunMode.isReview(context)) return defaultToolRunner.prepare(toolCollection, request, context);
        BaseTool tool = toolCollection.getOptional(request.getToolName()).orElse(null);
        if (tool instanceof RichTool richTool) {
            return defaultToolRunner.prepareResolved(tool, request, context, richTool.richToolType(), Instant.now());
        }
        return defaultToolRunner.prepare(toolCollection, request, context);
    }

    @Override
    public ToolExecutionResult execute(PreparedToolExecution prepared, AgentContext context) {
        if (AgentRunMode.isReview(context)) return defaultToolRunner.execute(prepared, context);
        if (prepared != null && prepared.isReady() && prepared.getTool() instanceof RichTool richTool) {
            return richTool.executeRich(prepared.getRequest(), context, prepared.getInvocation(), prepared.getStartedAt());
        }
        return defaultToolRunner.execute(prepared, context);
    }
}
