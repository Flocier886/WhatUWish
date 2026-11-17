package com.flocier.application.agent.runtimeevent;

import com.flocier.application.agent.execute.AgentExecuteCommand;
import com.flocier.application.agent.execute.react.ReactAgentExecuteStrategy;
import com.flocier.application.agent.desktopcontrol.DesktopControlApplicationService;
import com.flocier.application.agent.stream.NoopAgentSessionStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 为 Runtime Event API 调度非数据库注册的运行时 Agent。
 *
 * <p>该服务刻意不复用旧 {@code AgentDispatchService}：后者的职责是按旧 Agent
 * 配置表解析策略，而这里的 ReAct Runtime 已经是被目录明确声明的独立能力。</p>
 */
@Slf4j
@Service
public class RuntimeAgentExecutionService {

    @Resource
    private RuntimeAgentCatalog runtimeAgentCatalog;
    @Resource
    private ReactAgentExecuteStrategy reactAgentExecuteStrategy;
    @Resource
    private DesktopControlApplicationService desktopControlApplicationService;
    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    public void dispatch(AgentExecuteCommand command) {
        RuntimeAgentDescriptor agent = runtimeAgentCatalog.requireAvailableAgent(command.getAiAgentId());
        threadPoolExecutor.execute(() -> executeReact(command, agent));
    }

    public void dispatchResume(AgentExecuteCommand command) {
        RuntimeAgentDescriptor agent = runtimeAgentCatalog.requireAvailableAgent(command.getAiAgentId());
        threadPoolExecutor.execute(() -> {
            boolean success = false;
            try {
                reactAgentExecuteStrategy.execute(command, new NoopAgentSessionStream());
                success = true;
            } catch (Exception e) {
                log.error("desktop resume failed, controlId={}, runId={}", command.getResumeControlId(), command.getRunId(), e);
            } finally {
                desktopControlApplicationService.finish(command.getResumeControlId(), success);
            }
        });
    }

    private void executeReact(AgentExecuteCommand command, RuntimeAgentDescriptor agent) {
        try {
            reactAgentExecuteStrategy.execute(command, new NoopAgentSessionStream());
        } catch (Exception e) {
            log.error("runtime agent execute failed, agentId={}, runId={}, requestId={}",
                agent.getAgentId(), command.getRunId(), command.getRequestId(), e);
        }
    }
}
