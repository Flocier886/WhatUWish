package com.flocier.domain.agent.reactor.config;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.llm.config.LlmBehaviorConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmProviderConfig;
import com.flocier.domain.agent.runtime.llm.config.LlmSettings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Getter
@Configuration
public class ReactorConfig {

    private static final java.util.List<String> DEFAULT_SKILL_SCRIPT_RUNTIMES =
            java.util.List.of("python", "node", "shell", "powershell", "bat");

    // 主体沿用 master AgentPrompt.REACT_SYSTEM_PROMPT；工具名按本站现有 skill 工具合同适配。
    private static final String DEFAULT_REACT_SYSTEM_PROMPT = """
            # 角色
            你是 Reactor，专注深度调研与数据分析。帮助用户检索信息、分析数据、生成报告与可视化交付物。

             # 工作方式
             - Think, then act：非平凡工具序列前先简短规划；禁止投机式乱调工具。
             - 信息够了就停下并交付，不要无限深挖。
             - Smallest helpful response：先结论；禁止套话开场/收尾；短问一段话。
             - Cite what you did：动过文件/工具时，结尾摘要路径与关键数字；禁止重贴 tool 大输出、整表、HTML 正文。
             - Surface failures clearly：写清工具名与错误要点；相同失败入参不反复重试。
             - 只用当前会话提供给你的工具；不要假设未出现在工具列表中的能力。

             # 并行工具调用

             当你需要多个彼此独立的信息时，应在同一次回复中同时请求这些工具，
             不要每轮只调用一个工具。

             独立的文件读取、搜索、网页获取和只读命令，都应该批量放在同一个
             assistant turn 中。运行时会并发执行相互独立的调用，
             这样可以减少额外的往返和重复发送整个对话上下文。

             只有当后一个调用确实依赖前一个调用的结果时，才应该串行执行。
             例如：必须先读取文件，才能修改文件。

             如果不确定调用之间是否独立，且它们看起来互不依赖，就批量调用。
             记忆规则
             你拥有跨会话的持久记忆。

             若本轮提供 memory 工具，使用它保存以下长期事实：

             - 用户偏好
             - 环境信息
             - 工具的特殊行为
             - 稳定的项目约定

             记忆会被注入后续会话，因此必须保持简洁，只保存未来仍然有用的事实。

             优先保存能够减少用户未来重复纠正你的信息。
             用户偏好和反复出现的修正，比一次性任务细节更重要。

             不要把以下内容保存到 memory：

             - 当前任务进度
             - 已完成的工作记录
             - 临时 TODO
             - PR 编号
             - Issue 编号
             - Commit SHA
             - “已经修复某个 bug”
             - “已经提交某个 PR”
             - 阶段完成状态
             - 可能在七天内过期的数据

             短期过程应该通过 session_search 从历史会话中查找。

             如果你发现了一种以后可能复用的新方法，
             应当把它保存为 skill，而不是保存为 memory。

             记忆应该写成陈述性事实，而不是给未来自己的命令。

             正确：
             “用户偏好简洁回答。”

             错误：
             “始终简洁地回答。”

             正确：
             “项目使用 pytest 和 xdist。”

             错误：
             “运行 pytest -n 4。”

             流程和工作方法应该放进 skill，而不是 memory。

             会话搜索
             当用户提到过去的对话，或者你怀疑过去的会话中有相关信息时，
             在让用户重复说明之前，应先使用 session_search 查找历史内容。
             技能规则
             完成复杂任务、修复棘手问题，或发现非平凡工作流程后，
             应当封装成skill来保存这套方法，以便未来复用。

             如果发现正在使用的技能过时、不完整或错误，应使用 workspace_read 和 workspace_edit 修正 skills/<name>/ 下的文件，不要等用户要求。
             在执行依赖该技能的操作前，必须重新调用 skill_view 确认技能已经重新加载。

             系统提示中的技能列表只显示名称和简介。
             如果某个技能与当前任务匹配，甚至只是部分相关，必须使用 skill_view(name) 加载它并遵循其中的指令。
             如果无法判断技能名称，先调用 skills_search。
             不要使用普通工作区文件读取工具搜索 skill 主文档。
             skill_view 返回的附属资料可用 workspace_list、workspace_glob、workspace_grep 和 workspace_read 在 skills/ 路径下检查。
             需要执行技能脚本时，使用 bash 运行 skill_view 提供的 skills/<name>/scripts/ 命令；bash 与 code_execution 共用沙箱。
             宁可加载暂时用不到的技能，也不要漏掉包含关键步骤、陷阱和工作约定的技能。

            # 共享工作区
            会话工作区可沉淀搜索报告、分析报告与交付物，供后续步骤复用。
            路径可自定，但须稳定、可被 workspace 类只读工具发现；文件名或目录名宜含任务关键词。
            调研/分析若需跨步骤复用，应落成报告文件，而不是只留在对话里。
            """;

    private Map<String, String> reactSystemPromptMap = new HashMap<>();
    @Value("${reactor.agent.react.system-prompt-map:{}}")
    public void setReactSystemPromptMap(String raw) {
        this.reactSystemPromptMap = parseStringMap(raw);
    }

    @Value("${reactor.agent.react.model:}")
    private String reactModelName;

    @Value("${reactor.agent.react.max-step:10}")
    private Integer reactMaxSteps;

    @Value("${reactor.agent.react.temperature:0.2}")
    private Double reactTemperature;

    @Value("${reactor.agent.summary.model:}")
    private String summaryModelName;

    @Value("${reactor.agent.summary.temperature:0.2}")
    private Double summaryTemperature;

    @Value("${reactor.agent.summary.prompt:}")
    private String summaryPrompt;

    /** 与新版 master 一致，供宿主 CLI 与 Web Fetch 子进程复用可选代理。 */
    @Value("${autobots.autoagent.web_fetch_proxy:}")
    private String webFetchProxy;

    private Map<String, Map<String, Object>> toolConfigMap = new LinkedHashMap<>();
    @Value("${reactor.tool.tools:{}}")
    public void setToolConfigMap(String raw) {
        this.toolConfigMap = parseObjectMap(raw);
    }

    private Map<String, Object> toolDefaults = new LinkedHashMap<>();
    @Value("${reactor.tool.defaults:{}}")
    public void setToolDefaults(String raw) {
        this.toolDefaults = parseFlatObjectMap(raw);
    }

    private SkillRuntimeOptions skillRuntimeOptions = SkillRuntimeOptions.builder()
            .enabled(false)
            .directories(java.util.List.of("runtime/skills"))
            .reactEnabled(true)
            .workflowEnabled(false)
            .maxReadChars(16000)
            .maxListEntries(20)
            .maxSearchMatches(20)
            .maxExecutionSteps(3)
            .defaultScriptTimeoutSeconds(30)
            .maxScriptTimeoutSeconds(120)
            .allowedScriptRuntimes(DEFAULT_SKILL_SCRIPT_RUNTIMES)
            .compatibilityCheckEnabled(true)
            .allowFullContentRead(true)
            .scriptActionsEnabled(true)
            .masterStyleToolsEnabled(true)
            .legacySkillExecuteToolEnabled(false)
            .build();
    @Value("${reactor.skill.runtime:{}}")
    public void setSkillRuntimeOptions(String raw) {
        this.skillRuntimeOptions = parseSkillRuntimeOptions(raw);
    }

    private LlmSettings llmSettings = LlmSettings.builder().build();
    @Value("${reactor.llm.settings:{}}")
    public void setLlmSettings(String raw) {
        this.llmSettings = parseLlmSettings(raw);
    }

    public String resolveReactSystemPrompt() {
        return normalizeReactPrompt(reactSystemPromptMap.get("default"), DEFAULT_REACT_SYSTEM_PROMPT);
    }

    public String resolveSummaryPrompt() {
        return summaryPrompt == null ? "" : summaryPrompt;
    }

    public java.util.List<String> getDefaultEnabledTools() {
        Object value = toolDefaults.get("enabledTools");
        if (value instanceof java.util.List<?> list) {
            java.util.List<String> result = new java.util.ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return java.util.List.of();
    }

    private static Map<String, String> parseStringMap(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return new HashMap<>();
        }
        return JSON.parseObject(json, new TypeReference<Map<String, String>>() {
        });
    }

    private static Map<String, Map<String, Object>> parseObjectMap(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> root = JSON.parseObject(json, new TypeReference<Map<String, Object>>() {
        });
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            result.put(entry.getKey(), asMap(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Object> parseFlatObjectMap(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return new LinkedHashMap<>();
        }
        return JSON.parseObject(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private static LlmSettings parseLlmSettings(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return LlmSettings.builder().build();
        }

        Map<String, Object> root = JSON.parseObject(json, new TypeReference<Map<String, Object>>() {
        });
        Map<String, Object> defaults = asMap(root.get("defaults"));
        Map<String, Object> behavior = asMap(root.get("behavior"));
        Map<String, Object> modelsRaw = asMap(root.get("models"));
        Map<String, Object> providersRaw = asMap(root.get("providers"));

        Map<String, LlmModelConfig> models = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : modelsRaw.entrySet()) {
            Map<String, Object> item = asMap(entry.getValue());
            models.put(entry.getKey(), LlmModelConfig.builder()
                    .provider(asString(item.get("provider")))
                    .model(asString(item.get("model")))
                    .baseUrl(asString(item.get("baseUrl")))
                    .apiKey(asString(item.get("apiKey")))
                    .interfacePath(asString(item.get("interfacePath")))
                    .temperature(asDouble(item.get("temperature")))
                    .maxTokens(asInteger(item.get("maxTokens")))
                    .timeoutSeconds(asInteger(item.get("timeoutSeconds")))
                    .extParams(asMap(item.get("extParams")))
                    .build());
        }

        Map<String, LlmProviderConfig> providers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : providersRaw.entrySet()) {
            Map<String, Object> item = asMap(entry.getValue());
            providers.put(entry.getKey(), LlmProviderConfig.builder()
                    .baseUrl(asString(item.get("baseUrl")))
                    .apiKey(asString(item.get("apiKey")))
                    .interfacePath(asString(item.get("interfacePath")))
                    .extParams(asMap(item.get("extParams")))
                    .build());
        }

        return LlmSettings.builder()
                .defaultModel(asString(defaults.get("model")))
                .models(models)
                .providers(providers)
                .behavior(LlmBehaviorConfig.builder()
                        .extractUsage(asBoolean(behavior.get("extractUsage"), true))
                        .logRawResponse(asBoolean(behavior.get("logRawResponse"), false))
                        .enableFallback(asBoolean(behavior.get("enableFallback"), false))
                        .build())
                .build();
    }

    private static SkillRuntimeOptions parseSkillRuntimeOptions(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return SkillRuntimeOptions.builder()
                    .enabled(false)
                    .directories(java.util.List.of("runtime/skills"))
                    .reactEnabled(true)
                    .workflowEnabled(false)
                    .maxReadChars(16000)
                    .maxListEntries(20)
                    .maxSearchMatches(20)
                    .maxExecutionSteps(3)
                    .defaultScriptTimeoutSeconds(30)
                    .maxScriptTimeoutSeconds(120)
                    .allowedScriptRuntimes(DEFAULT_SKILL_SCRIPT_RUNTIMES)
                    .compatibilityCheckEnabled(true)
                    .allowFullContentRead(true)
                    .scriptActionsEnabled(true)
                    .masterStyleToolsEnabled(true)
                    .legacySkillExecuteToolEnabled(false)
                    .build();
        }
        Map<String, Object> root = JSON.parseObject(json, new TypeReference<Map<String, Object>>() {
        });
        return SkillRuntimeOptions.builder()
                .enabled(asBoolean(root.get("enabled"), false))
                .directories(asStringList(root.get("directories")))
                .reactEnabled(asBoolean(root.get("reactEnabled"), true))
                .workflowEnabled(asBoolean(root.get("workflowEnabled"), false))
                .maxReadChars(defaultInt(asInteger(root.get("maxReadChars")), 16000))
                .maxListEntries(defaultInt(asInteger(root.get("maxListEntries")), 20))
                .maxSearchMatches(defaultInt(asInteger(root.get("maxSearchMatches")), 20))
                .maxExecutionSteps(defaultInt(asInteger(root.get("maxExecutionSteps")), 3))
                .defaultScriptTimeoutSeconds(defaultInt(asInteger(root.get("defaultScriptTimeoutSeconds")), 30))
                .maxScriptTimeoutSeconds(defaultInt(asInteger(root.get("maxScriptTimeoutSeconds")), 120))
                .allowedScriptRuntimes(defaultStringList(
                        root.get("allowedScriptRuntimes"),
                        DEFAULT_SKILL_SCRIPT_RUNTIMES
                ))
                .compatibilityCheckEnabled(asBoolean(root.get("compatibilityCheckEnabled"), true))
                .allowFullContentRead(asBoolean(root.get("allowFullContentRead"), true))
                .scriptActionsEnabled(asBoolean(root.get("scriptActionsEnabled"), true))
                .masterStyleToolsEnabled(asBoolean(root.get("masterStyleToolsEnabled"), true))
                .legacySkillExecuteToolEnabled(asBoolean(root.get("legacySkillExecuteToolEnabled"), false))
                .build();
    }

    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static String asString(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static Integer asInteger(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            log.warn("failed to parse integer from reactor config value: {}", raw);
            return null;
        }
    }

    private static Double asDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            log.warn("failed to parse double from reactor config value: {}", raw);
            return null;
        }
    }

    private static Boolean asBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static java.util.List<String> asStringList(Object raw) {
        if (raw instanceof java.util.List<?> list) {
            java.util.List<String> result = new java.util.ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return java.util.List.of();
    }

    private static java.util.List<String> defaultStringList(Object raw, java.util.List<String> defaultValue) {
        java.util.List<String> parsed = asStringList(raw);
        return parsed.isEmpty() ? defaultValue : parsed;
    }

    private static int defaultInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    /** 旧模板含动态运行数据时回退稳定指令，避免把历史和任务放回 SYSTEM。 */
    private static String normalizeReactPrompt(String configuredPrompt, String defaultPrompt) {
        if (!StringUtils.hasText(configuredPrompt)) {
            return defaultPrompt;
        }
        for (String dynamicVariable : java.util.List.of("task", "query", "current_step", "max_step",
                "memory_prompt", "memory", "last_thought", "last_observation")) {
            if (configuredPrompt.contains("{{" + dynamicVariable + "}}")) {
                return defaultPrompt;
            }
        }
        return configuredPrompt;
    }
}
