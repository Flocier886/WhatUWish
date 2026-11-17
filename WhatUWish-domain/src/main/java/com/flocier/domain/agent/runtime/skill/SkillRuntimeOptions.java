package com.flocier.domain.agent.runtime.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill Runtime 统一配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillRuntimeOptions {

    /** 是否启用 skill runtime。 */
    private boolean enabled;
    /** skill 根目录列表。 */
    @Builder.Default
    private List<String> directories = new ArrayList<>();
    /** 是否允许 ReAct 注入 skill 工具。 */
    private boolean reactEnabled;
    /** 是否允许 workflow 注入 skill 工具。 */
    private boolean workflowEnabled;
    /** 读取 skill 文档时的最大字符数。 */
    private int maxReadChars;
    /** skill 列表最大返回条数。 */
    private int maxListEntries;
    /** 检索类输出的最大命中数预留值。 */
    private int maxSearchMatches;
    /** skill 默认最大执行步数。 */
    private int maxExecutionSteps;
    /** 默认脚本超时时间，单位秒。 */
    private int defaultScriptTimeoutSeconds;
    private int maxScriptTimeoutSeconds;
    @Builder.Default
    private List<String> allowedScriptRuntimes = new ArrayList<>();
    private boolean compatibilityCheckEnabled;
    private boolean allowFullContentRead;
    private boolean scriptActionsEnabled;
    /** 是否自动注入 master 风格 skill 工具链。 */
    private boolean masterStyleToolsEnabled;
    /** 是否自动暴露旧 skill_execute_tool 兼容入口，默认关闭。 */
    private boolean legacySkillExecuteToolEnabled;
}
