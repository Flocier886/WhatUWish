package com.flocier.domain.agent.runtime.memory.review;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.flocier.domain.agent.runtime.agent.*;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.agent.tool.*;
import com.flocier.domain.agent.runtime.llm.message.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** 对照 master LtmAgentForkSupport：冻结父前缀，只复用现有 ReAct，不进入 Summary 或主收尾。 */
@Component
@RequiredArgsConstructor
public class SkillReviewRunner {
    private final ReActAgent agent;
    private final DefaultToolCollectionFactory toolsFactory;
    private final SkillReviewFiles files;

    /** 跨线程只传值快照，不共享父消息、工具实例或可变 metadata。 */
    public record Snapshot(String sessionId, String requestId, String runId, String system,
                           List<LlmMessage> messages, String toolDescriptions, Map<String, Object> definitions) { }

    /** 冻结 SummaryAgent 前消息及父工具定义，内部可变对象也独立复制。 */
    public Snapshot snapshot(AgentContext parent) {
        // 数据库恢复的消息可能共享 List.of() 空集合；快照按值复制，禁止生成 $ref 引用。
        List<LlmMessage> messages = JSON.parseArray(JSON.toJSONString(parent.getReactMemorySnapshot(),
                SerializerFeature.DisableCircularReferenceDetect), LlmMessage.class);
        Map<String, Object> definitions = parent.getToolCollection() == null ? new LinkedHashMap<>()
                : JSON.parseObject(JSON.toJSONString(parent.getToolCollection().exportToolDefinitions(),
                        SerializerFeature.DisableCircularReferenceDetect), new TypeReference<Map<String, Object>>() { });
        return new Snapshot(parent.getSessionId(), parent.getRequestId(), parent.getAttribute("runId"),
                parent.getAttribute(ReActAgent.REACT_SYSTEM_PROMPT_KEY), messages,
                parent.getToolCollection() == null ? "" : parent.getToolCollection().describeTools(), definitions);
    }

    /** 构造无用户输出通道的复盘上下文，只绑定独立的 Skill 维护工具。 */
    public AgentContext createContext(Snapshot snapshot, SkillReviewExecution execution, SkillReviewState state) {
        ToolCollection tools = toolsFactory.createSkillReviewTools(files);
        AgentContext context = AgentContext.builder().sessionId(snapshot.sessionId()).requestId(execution.getForkRequestId())
                .traceId(execution.getReviewId()).runMode(AgentRunMode.SKILL_REVIEW).skillReviewState(state)
                .maxStep(execution.getMaxSteps()).toolCollection(tools).build();
        context.setAttribute("runId", execution.getReviewId());
        context.setAttribute(ReActAgent.REACT_SYSTEM_PROMPT_KEY, snapshot.system());
        context.getMemory().addAll(JSON.parseArray(JSON.toJSONString(snapshot.messages(),
                SerializerFeature.DisableCircularReferenceDetect), LlmMessage.class));
        context.appendMemory(LlmMessage.builder().role(LlmMessageRole.USER).content(SkillReviewPrompt.DIRECTIVE).build());
        Map<String, Object> definitions = new LinkedHashMap<>(snapshot.definitions());
        // 父定义保留，补齐本次允许的维护工具；当前 Registry 摘要由新工具提供。
        tools.exportToolDefinitions().forEach(definitions::putIfAbsent);
        context.setAttribute("review.toolDefinitions", definitions);
        context.setAttribute("review.toolDescriptions", snapshot.toolDescriptions() + "\n" + tools.describeTools());
        return context;
    }

    /** 返回是否正常终答；步数耗尽、空答和未恢复工具错误都属于失败。 */
    public boolean run(Snapshot snapshot, SkillReviewExecution execution, SkillReviewState state) {
        AgentContext context = createContext(snapshot, execution, state);
        agent.run(context);
        state.checkActive();
        if ("max_step_reached".equals(context.getFinishReason()) || state.hasUnresolvedTools() || context.getMemory().isEmpty()) return false;
        LlmMessage last = context.getMemory().get(context.getMemory().size() - 1);
        return last.getRole() == LlmMessageRole.ASSISTANT && last.getContent() != null && !last.getContent().isBlank()
                && (last.getToolCalls() == null || last.getToolCalls().isEmpty());
    }
}
