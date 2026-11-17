package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.*;
import com.flocier.domain.agent.runtime.memory.review.SkillReviewFiles;
import lombok.RequiredArgsConstructor;
import java.util.*;

/** 复盘专用 write/edit 适配器；真实落盘后才返回成功，不登记 Artifact 或前端文件。 */
@RequiredArgsConstructor
public class SkillWriteTool implements BaseTool {
    private final SkillReviewFiles files;
    private final boolean edit;
    public String name() { return edit ? "skill_edit_tool" : "skill_write_tool"; }
    public String description() { return edit
            ? "Edit a skill text file. Read it first with skill_read_tool; old_string must match exactly and uniquely unless replace_all=true."
            : "Write UTF-8 skill files (SKILL.md, scripts/, references/, templates/). Read existing files first. Use class-level skill_name and package-relative path."; }
    /** 参数只允许包名和包内路径；编辑复用 master 的精确文本替换语义。 */
    public Object params() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String key : edit ? List.of("skill_name", "path", "old_string", "new_string") : List.of("skill_name", "path", "content"))
            properties.put(key, Map.of("type", "string"));
        if (edit) properties.put("replace_all", Map.of("type", "boolean"));
        return Map.of("type", "object", "properties", properties, "required", edit
                ? List.of("skill_name", "path", "old_string", "new_string") : List.of("skill_name", "path", "content"));
    }
    /** 由文件服务完成权限、版本和落盘检查，结果同时供模型观察及后台审计使用。 */
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> output = files.write(context, ((ToolInvokeRequest) input).getArguments(), edit);
            return ToolResultPayload.success("Skill 文件操作完成", output.toString(), output);
        } catch (Exception e) {
            return ToolResultPayload.builder().success(false).status("failed").errorCode("skill_write_failed")
                    .errorMessage(e.getMessage()).toolResult(e.getMessage()).llmObservation(e.getMessage()).build();
        }
    }
}
