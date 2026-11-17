package com.flocier.domain.agent.runtime.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 静态定义对象。
 * 统一承载 skill 的元数据、说明文档、提示词模板与受限工具需求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    /** skill 唯一标识。 */
    private String skillId;
    /** skill 程序化名称。 */
    private String name;
    /** skill 标题。 */
    private String title;
    /** skill 简介。 */
    private String description;
    /** 检索和提示词索引使用的分类。 */
    @Builder.Default
    private String category = "general";
    /** 适用场景说明。 */
    private String scenario;
    /** 版本号。 */
    private String version;
    private String sourceId;
    private String owner;
    /** skill 根目录绝对路径。 */
    private Path basePath;
    /** SKILL.md 的完整内容。 */
    private String content;
    private String instructionSummary;
    private String contentRef;

    /** 提示词模板内容，key 例如 system、summary。 */
    @Builder.Default
    private Map<String, String> promptTemplates = new LinkedHashMap<>();

    /** skill 可使用的工具白名单。 */
    @Builder.Default
    private List<String> toolRequirements = new ArrayList<>();

    /** 输入结构定义，先用通用 map 承载。 */
    @Builder.Default
    private Map<String, Object> inputSchema = new LinkedHashMap<>();

    /** 输出结构定义，先用通用 map 承载。 */
    @Builder.Default
    private Map<String, Object> outputSchema = new LinkedHashMap<>();

    /** skill 关联脚本定义。 */
    @Builder.Default
    private List<SkillScriptDefinition> scripts = new ArrayList<>();

    @Builder.Default
    private List<String> references = new ArrayList<>();

    @Builder.Default
    private List<String> assets = new ArrayList<>();

    /** 标签。 */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** 运行约束。 */
    @Builder.Default
    private List<String> constraints = new ArrayList<>();

    @Builder.Default
    private boolean enabled = true;

    private String compatibilityStatus;
}
