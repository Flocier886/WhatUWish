package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.skill.SkillExecutionResult;
import com.flocier.domain.agent.runtime.skill.SkillExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 兼容或高级封装入口。
 *
 * <p>普通 skill 使用默认走主 agent 的 master 风格工具链；
 * 本工具保留给历史调用和需要独立 skill 子链路的高级场景。</p>
 */
@RequiredArgsConstructor
public class SkillExecuteTool implements BaseTool {

    private final String name;
    private final String description;
    private final Object params;
    private final SkillExecutor skillExecutor;

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Object params() {
        return params;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request)) {
            throw new IllegalArgumentException("SkillExecuteTool only accepts ToolInvokeRequest");
        }
        Map<String, Object> arguments = request.getArguments() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getArguments());
        String skillName = stringValue(arguments.remove("skill_name"));
        if (!StringUtils.hasText(skillName)) {
            throw new IllegalArgumentException("skill_name is required");
        }

        Object nestedArguments = arguments.get("arguments");
        Map<String, Object> skillArguments;
        if (nestedArguments instanceof Map<?, ?> map) {
            skillArguments = new LinkedHashMap<>();
            map.forEach((key, value) -> skillArguments.put(String.valueOf(key), value));
        } else {
            skillArguments = arguments;
        }
        if (!skillArguments.containsKey("task") && StringUtils.hasText(stringValue(arguments.get("task")))) {
            skillArguments.put("task", arguments.get("task"));
        }
        if (StringUtils.hasText(stringValue(arguments.get("action_name")))) {
            skillArguments.put("action_name", arguments.get("action_name"));
        }
        if (StringUtils.hasText(stringValue(arguments.get("execution_mode")))) {
            skillArguments.put("execution_mode", arguments.get("execution_mode"));
        }
        return skillExecutor.execute(skillName, skillArguments, context, request.getToolCallId());
    }

    private String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
