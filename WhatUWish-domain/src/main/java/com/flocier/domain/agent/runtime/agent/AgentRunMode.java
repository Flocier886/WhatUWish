package com.flocier.domain.agent.runtime.agent;

import java.util.Map;
import java.util.Set;

/** 服务端单次运行边界；默认主任务，复盘只复用决策循环，不产生主链副作用。 */
public enum AgentRunMode {
    MAIN, SKILL_REVIEW;

    public static final String ATTRIBUTE = "agentRunMode";
    public static final Set<String> REVIEW_TOOLS = Set.of("skill_view", "skills_search",
            "workspace_list", "workspace_glob", "workspace_grep", "workspace_read",
            "workspace_write", "workspace_edit");

    public static boolean isReview(AgentContext context) {
        return context != null && context.getRunMode() == SKILL_REVIEW;
    }

    /** 仅由 ReAct 构造的请求属性传递模式，工具参数不能覆盖此属性。 */
    public static boolean isReview(Map<String, Object> attributes) {
        return attributes != null && SKILL_REVIEW.name().equals(attributes.get(ATTRIBUTE));
    }

    public static void checkTool(AgentContext context, String name) {
        if (isReview(context)) {
            context.getSkillReviewState().checkActive();
            if (!REVIEW_TOOLS.contains(name)) throw new IllegalArgumentException("复盘禁止调用工具：" + name);
        }
    }
}
