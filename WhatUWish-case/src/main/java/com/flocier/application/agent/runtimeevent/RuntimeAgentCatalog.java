package com.flocier.application.agent.runtimeevent;

import com.flocier.types.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runtime Event API 可启动能力的权威目录。
 *
 * <p>新 ReAct Runtime 的装配由 Spring 和领域运行时提供，不依赖旧 Agent 配置表；
 * 因此不能通过 {@code AiAgentController} 的数据库查询接口发现它。</p>
 */
@Service
public class RuntimeAgentCatalog {

    public static final String REACT_RUNTIME_AGENT_ID = "react-runtime";

    private static final RuntimeAgentDescriptor REACT_RUNTIME = new RuntimeAgentDescriptor(
            REACT_RUNTIME_AGENT_ID,
            "ReAct Runtime",
            "使用当前 ReAct 工作流、工具、ledger 与 artifact 能力执行任务。",
            "REACT",
            true
    );

    public List<RuntimeAgentDescriptor> listAvailableAgents() {
        return List.of(REACT_RUNTIME);
    }

    public RuntimeAgentDescriptor requireAvailableAgent(String agentId) {
        if (REACT_RUNTIME_AGENT_ID.equals(agentId) && REACT_RUNTIME.isAvailable()) {
            return REACT_RUNTIME;
        }
        throw new BizException("当前 Runtime Event API 不支持启动 agentId: " + agentId);
    }
}
