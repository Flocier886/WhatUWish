package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillPromptIndexBuilder;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillScriptDefinition;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按需加载完整 skill 文档；超长 observation 在工具执行层保存为可续读文件。 */
public class SkillViewTool implements BaseTool {
    private final SkillRegistry registry;
    private final SkillPathGuard pathGuard;

    public SkillViewTool(SkillRegistry registry, SkillPathGuard pathGuard) {
        this.registry = registry;
        this.pathGuard = pathGuard;
    }

    @Override public String name() { return "skill_view"; }
    @Override public String description() {
        return "加载指定 skill 的完整 SKILL.md 内容或其附属文件。如果不知道 skill 名称，先使用 skills_search。";
    }
    @Override public Object params() {
        return Map.of("type", "object", "properties", Map.of(
                "name", Map.of("type", "string", "description", "要加载的 skill 名称"),
                "file_path", Map.of("type", "string", "description", "可选，skill 根目录内的相对路径")
        ), "required", List.of("name"));
    }

    public String promptIndex() { return SkillPromptIndexBuilder.build(registry.listSkills()); }

    @Override public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> args = input instanceof ToolInvokeRequest request && request.getArguments() != null
                    ? request.getArguments() : Map.of();
            String name = args.get("name") == null ? "" : String.valueOf(args.get("name")).trim();
            if (name.isBlank()) return failure("name is required", Map.of());
            SkillDefinition skill = registry.getRequiredSkill(name);
            String filePath = args.get("file_path") == null ? "" : String.valueOf(args.get("file_path")).trim();
            return filePath.isBlank() ? document(skill) : linkedFile(skill, filePath);
        } catch (Exception failure) {
            return failure(failure.getMessage() == null ? "skill_view failed" : failure.getMessage(), Map.of());
        }
    }

    private ToolResultPayload document(SkillDefinition skill) throws Exception {
        String content = skill.getBasePath() == null ? skill.getContent()
                : Files.readString(pathGuard.assertPathUnderRoot(skill.getBasePath().resolve("SKILL.md"),
                        skill.getBasePath()), StandardCharsets.UTF_8);
        if (content == null) content = "";
        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) content = content.substring(end + 4).trim();
        }
        String dir = "skills/" + skill.getName();
        content = content.replace("${SKILL_DIR}", dir).replace("${PYTHON}", "python");
        List<Map<String, String>> linked = new ArrayList<>();
        for (String path : skill.getReferences() == null ? List.<String>of() : skill.getReferences())
            linked.add(Map.of("path", path, "kind", "reference"));
        for (String path : skill.getAssets() == null ? List.<String>of() : skill.getAssets())
            linked.add(Map.of("path", path, "kind", "asset"));
        List<String> scripts = new ArrayList<>();
        for (SkillScriptDefinition script : skill.getScripts() == null ? List.<SkillScriptDefinition>of() : skill.getScripts()) {
            String relative = script.getRelativePath();
            String runtime = script.getRuntime() == null ? "python" : script.getRuntime();
            String executable = switch (runtime.toLowerCase()) {
                case "node" -> "node";
                case "shell", "bash" -> "bash";
                case "powershell" -> "powershell -File";
                default -> "python";
            };
            scripts.add("- " + script.getScriptName() + " | runtime=" + runtime + " | path=" + relative
                    + " | run: " + executable + " " + dir + "/" + relative
                    + " | " + (script.getDescription() == null ? "" : script.getDescription()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", skill.getName());
        data.put("id", skill.getSkillId());
        data.put("description", skill.getDescription());
        data.put("skillDir", dir);
        data.put("linkedFiles", linked);
        data.put("availableScripts", scripts);
        data.put("content", content);
        String observation = content;
        return ToolResultPayload.success("Read skill " + skill.getName(), observation, data);
    }

    private ToolResultPayload linkedFile(SkillDefinition skill, String filePath) throws Exception {
        if (skill.getBasePath() == null) return failure("skill package has no file path", Map.of());
        if (Path.of(filePath).isAbsolute() || filePath.startsWith("/") || filePath.contains(":"))
            return failure("file_path must be a relative path inside the skill directory", Map.of());
        Path path = pathGuard.assertPathUnderRoot(skill.getBasePath().resolve(filePath), skill.getBasePath());
        if (!Files.isRegularFile(path)) {
            List<String> available = new ArrayList<>();
            try (var files = Files.walk(skill.getBasePath())) {
                files.filter(Files::isRegularFile).limit(100).forEach(file -> available.add(
                        skill.getBasePath().relativize(file).toString().replace('\\', '/')));
            }
            return failure("Skill file not found: " + filePath, Map.of("available_files", available));
        }
        String content;
        boolean binary = false;
        try { content = Files.readString(path, StandardCharsets.UTF_8); }
        catch (CharacterCodingException failure) {
            binary = true;
            content = "[Binary file: " + path.getFileName() + ", size: " + Files.size(path) + " bytes]";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", skill.getName());
        data.put("file", filePath);
        data.put("content", content);
        data.put("is_binary", binary);
        String observation = filePath + "\n\n" + content;
        return ToolResultPayload.success("Read skill file " + filePath, observation, data);
    }

    private ToolResultPayload failure(String message, Map<String, Object> detail) {
        return ToolResultPayload.builder().success(false).status("failed")
                .errorCode("skill_view_failed").errorMessage(message).structuredOutput(detail)
                .toolResult(message).llmObservation(message + (detail.isEmpty() ? "" : "；可用文件：" + detail))
                .build();
    }
}
