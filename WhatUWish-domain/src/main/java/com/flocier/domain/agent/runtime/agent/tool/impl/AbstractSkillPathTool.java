package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * skill 包内文件探索工具的公共基类。
 *
 * <p>所有子类都必须通过 {@link SkillPathGuard} 把模型传入路径限制在当前 skill 根目录内。</p>
 */
@RequiredArgsConstructor
abstract class AbstractSkillPathTool implements BaseTool {

    private final String name;
    private final String description;
    private final Object params;
    protected final SkillRegistry skillRegistry;
    protected final SkillRuntimeOptions options;
    protected final SkillPathGuard pathGuard;

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

    protected Map<String, Object> arguments(Object input) {
        return input instanceof ToolInvokeRequest request && request.getArguments() != null
                ? request.getArguments()
                : Map.of();
    }

    protected SkillDefinition requireSkill(Map<String, Object> args) {
        String skillName = stringValue(args.get("skill_name"));
        if (!StringUtils.hasText(skillName)) {
            throw new IllegalArgumentException("skill_name is required");
        }
        return skillRegistry.getRequiredSkill(skillName);
    }

    /** 仅复盘允许定位尚未注册的新包，主任务仍沿用 Registry 合同。 */
    protected SkillDefinition requireSkill(Map<String, Object> args, AgentContext context) {
        if (com.flocier.domain.agent.runtime.agent.AgentRunMode.isReview(context)) {
            context.getSkillReviewState().checkActive();
            return com.flocier.domain.agent.runtime.memory.review.SkillReviewFiles.resolvePackage(
                    stringValue(args.get("skill_name")), skillRegistry, options, pathGuard);
        }
        return requireSkill(args);
    }

    /**
     * 把模型传入的相对路径锁定到当前 skill 包根目录下。
     */
    protected Path resolveSkillPath(SkillDefinition skill, String rawPath) {
        String relative = StringUtils.hasText(rawPath) ? rawPath : ".";
        if (Path.of(relative).isAbsolute() || relative.contains(":")) throw new IllegalArgumentException("path 必须为包内相对路径");
        return pathGuard.assertPathUnderRoot(skill.getBasePath().resolve(relative), skill.getBasePath());
    }

    protected int intValue(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected boolean boolValue(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    protected String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    protected String relative(SkillDefinition skill, Path path) {
        return skill.getBasePath().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    protected ToolResultPayload failed(String code, String message) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("errorCode", code);
        output.put("errorMessage", message);
        return ToolResultPayload.builder()
                .success(false)
                .status("failed")
                .errorCode(code)
                .errorMessage(message)
                .toolResult(message)
                .llmObservation(message)
                .structuredOutput(output)
                .build();
    }

    @Override
    public abstract Object execute(Object input, AgentContext context);
}
