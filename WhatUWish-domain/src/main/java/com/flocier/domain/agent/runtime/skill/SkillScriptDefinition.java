package com.flocier.domain.agent.runtime.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 包内的注册脚本定义。
 *
 * <p>脚本路径必须来自解析后的 skill 包声明，不能来自模型或用户任意输入。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillScriptDefinition {

    /** 脚本在当前 skill 内的调用名称。 */
    private String scriptName;
    /** 相对 skill 根目录的脚本路径。 */
    private String relativePath;
    /** 解析后的脚本绝对路径，只供系统内部校验和执行桥接使用。 */
    private Path absolutePath;
    /** 运行时类型，例如 python、node、shell、powershell、bat。 */
    private String runtime;
    /** 面向 agent 的脚本用途说明。 */
    private String description;
    /** 超时时间，单位秒。 */
    private Integer timeoutSeconds;
    /** 脚本来源：scripts_dir 表示自动扫描，scripts_yaml 表示显式声明。 */
    private String declarationSource;
    /** 社区脚本声明里的扩展字段，保留给参数提示、输出提示等后续能力。 */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String toSummaryLine() {
        return scriptName + " [" + runtime + "] " + relativePath
                + (description == null || description.isBlank() ? "" : " - " + description);
    }
}
