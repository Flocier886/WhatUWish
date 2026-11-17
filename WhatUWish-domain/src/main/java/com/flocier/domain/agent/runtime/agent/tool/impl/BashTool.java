package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与 code_execution 共用 local/E2B 后端，E2B 模式还与桌面接管共用沙箱。 */
public final class BashTool extends MaterialTool {
    public static final String NAME = "bash";
    private static final String DESCRIPTION = "在远端沙箱执行 shell，与 code_execution 共用 local 或 E2B 后端。"
            + "E2B 模式还与可视桌面共用环境，可检查浏览器状态、登录后的页面及下载文件。"
            + "E2B 沙箱内的 OpenCLI 可通过 opencli doctor、opencli browser ... 使用；先查询对应命令帮助。"
            + "先用 workspace_list 查看已授权的 inputs/ 文件；命令使用相对路径，生成需交付的文件写入 outputs/。"
            + "Bash 生成的有效 outputs/ 文件会自动登记为 Artifact，无需再调用 workspace_register。"
            + "已注册的 skill 位于工作区 skills/<name>/，可用 python skills/<name>/scripts/xxx.py 执行；修改会回写 skill 库。"
            + "返回退出码、stdout、stderr 和超时事实；非零退出码表示命令失败，应据实际输出判断下一步。"
            + "不要使用 Java 宿主机绝对路径。默认超时 120 秒，上限 600 秒。";
    private final SkillRegistry skillRegistry;
    private final SkillRuntimeOptions skillOptions;

    public BashTool(String pythonService, PythonToolClient client, MaterialFileAccessResolver access,
                    ArtifactContentWriter writer, ArtifactContentRemover remover,
                    String writeStorageType, String writePrefix,
                    SkillRegistry skillRegistry, SkillRuntimeOptions skillOptions) {
        super(NAME, DESCRIPTION, paramsDefinition(), pythonService, client, access,
                writer, remover, writeStorageType, writePrefix);
        this.skillRegistry = skillRegistry;
        this.skillOptions = skillOptions;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        Object result = super.execute(input, context);
        if (result instanceof ToolResultPayload payload && payload.getArtifactDescriptors() != null
                && !payload.getArtifactDescriptors().isEmpty()) {
            payload.setLlmObservation((payload.getLlmObservation() == null ? "" : payload.getLlmObservation())
                    + "\n这些文件已自动登记为 Artifact；请使用下方关联产物的 artifactKey，勿再调用 workspace_register。");
        }
        return result;
    }

    @Override
    protected PythonToolResponseEntity invokePython(ToolInvokeRequest request, AgentContext context,
                                                    MaterialFileAccessResolver.Prepared prepared) {
        Map<String, Object> arguments = new LinkedHashMap<>(prepared.arguments());
        Map<String, String> skillPaths = new LinkedHashMap<>();
        if (skillRegistry != null && skillOptions != null && skillOptions.isEnabled()) {
            for (SkillDefinition skill : skillRegistry.listSkills()) {
                if (skill.isEnabled() && skill.getName() != null && skill.getBasePath() != null)
                    skillPaths.put(skill.getName(), skill.getBasePath().toAbsolutePath().normalize().toString());
            }
            arguments.put("__skillPaths", skillPaths);
            for (String directory : skillOptions.getDirectories() == null ? List.<String>of() : skillOptions.getDirectories()) {
                if (directory != null && !directory.isBlank()) {
                    Path root = SkillPathGuard.resolveConfiguredRoot(directory);
                    arguments.put("__skillLibraryRoot", root.toString());
                    break;
                }
            }
        }
        return super.invokePython(request, context,
                new MaterialFileAccessResolver.Prepared(arguments, prepared.files(), prepared.workspaceRoot()));
    }

    private static Map<String, Object> paramsDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of("type", "string", "description", "在 E2B 会话工作区执行的 shell 命令"));
        properties.put("timeout_sec", Map.of("type", "integer", "minimum", 1, "maximum", 600,
                "description", "超时秒数，默认 120 秒"));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("command"), "additionalProperties", false);
    }
}
