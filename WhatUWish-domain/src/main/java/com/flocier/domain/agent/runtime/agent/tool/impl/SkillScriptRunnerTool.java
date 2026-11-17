package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillScriptDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行 skill 已注册脚本的 agent-facing 工具。
 *
 * <p>模型只传 skill_name 和 script_name；真实 scriptPath 由 registry 解析后交给 Python runner，
 * 避免模型直接指定任意本机脚本路径。</p>
 */
/** @deprecated 主 Agent 使用 bash 在统一沙箱执行 skills/ 脚本。 */
@RequiredArgsConstructor
@Deprecated
public class SkillScriptRunnerTool implements BaseTool {

    private static final String PYTHON_TOOL_NAME = "skill_script_runner";

    private final String name;
    private final String description;
    private final Object params;
    private final SkillRegistry skillRegistry;
    private final SkillRuntimeOptions options;
    private final String pythonService;
    private final String pythonEndpoint;
    private final PythonToolClient pythonToolClient;

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
    public Object execute(Object input, AgentContext context) {
        Map<String, Object> args = input instanceof ToolInvokeRequest request && request.getArguments() != null
                ? request.getArguments()
                : Map.of();
        try {
            String skillName = stringValue(args.get("skill_name"));
            String scriptName = stringValue(args.get("script_name"));
            if (!StringUtils.hasText(skillName) || !StringUtils.hasText(scriptName)) {
                return failed("skill_script_arguments_missing", "skill_name and script_name are required");
            }
            SkillDefinition skill = skillRegistry.getRequiredSkill(skillName);
            SkillScriptDefinition script = skillRegistry.getRequiredScript(skillName, scriptName);
            Integer timeout = boundedTimeout(args.get("timeout_seconds"), script.getTimeoutSeconds());
            Map<String, Object> runnerArgs = new LinkedHashMap<>();
            runnerArgs.put("requestId", UUID.randomUUID().toString());
            runnerArgs.put("skillExecutionId", "master-style:" + UUID.randomUUID());
            runnerArgs.put("skillName", skill.getName());
            runnerArgs.put("skillVersion", skill.getVersion());
            runnerArgs.put("skillBasePath", skill.getBasePath().toString());
            runnerArgs.put("actionName", script.getScriptName());
            runnerArgs.put("scriptName", script.getScriptName());
            // 这里只传注册脚本的相对路径，Python runner 会再次校验路径不得逃逸 skill 包。
            runnerArgs.put("scriptPath", script.getRelativePath());
            runnerArgs.put("runtime", script.getRuntime());
            runnerArgs.put("arguments", args.get("arguments") instanceof Map<?, ?> map ? copyMap(map) : Map.of());
            runnerArgs.put("argv", args.get("argv") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of());
            runnerArgs.put("timeoutSeconds", timeout);
            runnerArgs.put("artifactPolicy", "optional");

            PythonToolResponseEntity response = pythonToolClient.execute(
                    pythonService,
                    pythonEndpoint,
                    PYTHON_TOOL_NAME,
                    ToolInvokeRequest.builder()
                            .toolName(PYTHON_TOOL_NAME)
                            .toolCallId(name + ":" + script.getScriptName())
                            .arguments(runnerArgs)
                            .requestId(stringValue(runnerArgs.get("requestId")))
                            .sourceHint("skill")
                            .metadata(Map.of("skillName", skill.getName(), "scriptName", script.getScriptName()))
                            .build(),
                    context
            );
            return toPayload(skill, script, response);
        } catch (Exception e) {
            return failed("skill_script_runner_failed", e.getMessage());
        }
    }

    private ToolResultPayload toPayload(SkillDefinition skill, SkillScriptDefinition script, PythonToolResponseEntity response) {
        Map<String, Object> structured = response.getStructured() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(response.getStructured());
        structured.putIfAbsent("skillName", skill.getName());
        structured.putIfAbsent("scriptName", script.getScriptName());
        structured.putIfAbsent("actionName", script.getScriptName());
        structured.putIfAbsent("runtime", script.getRuntime());
        List<String> artifactRefs = extractArtifactRefs(response, structured);
        String observation = StringUtils.hasText(response.getLlmObservation())
                ? response.getLlmObservation()
                : SkillObservationFormatter.scriptResult(skill, script, response, structured, artifactRefs);
        return ToolResultPayload.builder()
                .success(response.isSuccess())
                .status(StringUtils.hasText(response.getStatus()) ? response.getStatus() : (response.isSuccess() ? "success" : "failed"))
                .toolResult(StringUtils.hasText(response.getOutput()) ? response.getOutput() : String.valueOf(structured))
                .llmObservation(observation)
                .structuredOutput(structured)
                .ledgerFacts(response.getLedgerFacts() == null ? List.of() : response.getLedgerFacts())
                .artifactRefs(artifactRefs)
                .errorCode(response.getErrorCode())
                .errorMessage(response.getErrorMessage())
                .metadata(response.getMetadata() == null ? new LinkedHashMap<>() : response.getMetadata())
                .build();
    }

    /**
     * Python runner 会把脚本产物放在 structured.artifactRefs 中；
     * 这里统一抽取成 Java 工具结果的 artifactRefs，方便 ledger 和后续交付复用。
     */
    private List<String> extractArtifactRefs(PythonToolResponseEntity response, Map<String, Object> structured) {
        List<String> direct = response.getArtifacts() == null ? List.of() : response.getArtifacts().stream()
                .map(item -> firstNonBlank(
                        item.get("artifactRef") == null ? null : String.valueOf(item.get("artifactRef")),
                        item.get("storageRef") == null ? null : String.valueOf(item.get("storageRef")),
                        item.get("path") == null ? null : String.valueOf(item.get("path")),
                        item.get("fileName") == null ? null : String.valueOf(item.get("fileName")),
                        item.get("name") == null ? null : String.valueOf(item.get("name"))
                ))
                .filter(StringUtils::hasText)
                .toList();
        if (!direct.isEmpty()) {
            return direct;
        }
        Object refs = structured.get("artifactRefs");
        if (!(refs instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (item instanceof Map<?, ?> map) {
                        return firstNonBlank(
                                map.get("artifactRef") == null ? null : String.valueOf(map.get("artifactRef")),
                                map.get("storageRef") == null ? null : String.valueOf(map.get("storageRef")),
                                map.get("path") == null ? null : String.valueOf(map.get("path")),
                                map.get("fileName") == null ? null : String.valueOf(map.get("fileName")),
                                map.get("name") == null ? null : String.valueOf(map.get("name"))
                        );
                    }
                    return item == null ? null : String.valueOf(item);
                })
                .filter(StringUtils::hasText)
                .toList();
    }

    private Integer boundedTimeout(Object raw, Integer declared) {
        int timeout = declared == null ? options.getDefaultScriptTimeoutSeconds() : declared;
        if (raw instanceof Number number) {
            timeout = number.intValue();
        } else if (raw != null) {
            try {
                timeout = Integer.parseInt(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
                timeout = declared == null ? options.getDefaultScriptTimeoutSeconds() : declared;
            }
        }
        int max = options.getMaxScriptTimeoutSeconds() <= 0 ? 120 : options.getMaxScriptTimeoutSeconds();
        return Math.max(1, Math.min(timeout, max));
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private ToolResultPayload failed(String code, String message) {
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
}
