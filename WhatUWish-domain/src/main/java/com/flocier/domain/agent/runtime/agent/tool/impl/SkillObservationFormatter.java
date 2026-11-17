package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillScriptDefinition;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * skill 工具专用 observation 格式化器。
 *
 * <p>这里只整理工具已经得到的事实，不从 structuredOutput 反向猜测业务语义。</p>
 */
final class SkillObservationFormatter {

    private static final int MAX_CONTENT_CHARS = 8000;
    private static final int MAX_LIST_ITEMS = 40;
    private static final int MAX_SCRIPT_OUTPUT_CHARS = 4000;

    private SkillObservationFormatter() {
    }

    static String skillList(List<Map<String, Object>> skills, int totalCount, boolean truncated) {
        StringBuilder builder = new StringBuilder();
        builder.append("已发现可用 skill：展示 ").append(skills.size()).append(" 个");
        if (totalCount >= 0) {
            builder.append("，总数 ").append(totalCount).append(" 个");
        }
        if (truncated) {
            builder.append("，已截断");
        }
        builder.append("。\n\n");
        for (int i = 0; i < Math.min(skills.size(), MAX_LIST_ITEMS); i++) {
            Map<String, Object> skill = skills.get(i);
            builder.append(i + 1).append(". ")
                    .append(value(skill.get("name")));
            appendOptional(builder, " - ", value(skill.get("description")));
            appendOptional(builder, "\n   场景：", value(skill.get("scenario")));
            appendOptional(builder, "\n   脚本数：", value(skill.get("scriptCount")));
            Object scripts = skill.get("scripts");
            if (scripts instanceof List<?> list && !list.isEmpty()) {
                builder.append("\n   可用脚本：");
                appendScriptSummary(builder, list);
            }
            builder.append('\n');
        }
        if (truncated || skills.size() > MAX_LIST_ITEMS) {
            builder.append("\n结果已截断；如需使用某个 skill，请用 skill_name 再读取详情。\n");
        }
        builder.append("\n后续建议：选择最匹配任务的 skill 后，再调用 skill_tool 读取完整说明。");
        return builder.toString();
    }

    static String skillDetail(SkillDefinition skill, Map<String, Object> payload) {
        StringBuilder builder = new StringBuilder();
        builder.append("已读取 skill：").append(skill.getName()).append('\n');
        appendOptional(builder, "标题：", skill.getTitle());
        appendOptional(builder, "描述：", skill.getDescription());
        appendOptional(builder, "场景：", skill.getScenario());
        appendOptional(builder, "版本：", skill.getVersion());
        appendOptional(builder, "说明文件：", skill.getContentRef());

        List<SkillScriptDefinition> scripts = skill.getScripts() == null ? List.of() : skill.getScripts();
        if (!scripts.isEmpty()) {
            builder.append("\n可用脚本：\n");
            for (SkillScriptDefinition script : scripts) {
                builder.append("- ").append(script.toSummaryLine()).append('\n');
            }
        }

        appendStringList(builder, "references", skill.getReferences());
        appendStringList(builder, "assets", skill.getAssets());

        Object content = payload.get("content");
        if (content instanceof String text && StringUtils.hasText(text)) {
            builder.append("\n===== SKILL.md 预览 =====\n")
                    .append(clip(text, MAX_CONTENT_CHARS))
                    .append('\n');
        } else {
            builder.append("\n当前未返回完整 SKILL.md 内容；如需要正文，请设置 include_content=true 或使用 skill_read_tool 读取。\n");
        }
        builder.append("\n后续建议：根据说明选择读取 references、搜索关键词，或用 skill_script_runner_tool 执行注册脚本。");
        return builder.toString();
    }

    static String readFile(String skillName, String path, int startLine, int shownLines, int totalLines,
                           boolean truncated, String content) {
        StringBuilder builder = new StringBuilder();
        builder.append("已读取 skill ").append(skillName).append(" 包内文件：").append(path).append('\n')
                .append("展示内容：第 ").append(startLine).append("-")
                .append(Math.max(startLine, startLine + Math.max(shownLines, 1) - 1)).append(" 行");
        if (totalLines >= 0) {
            builder.append("，共 ").append(totalLines).append(" 行");
        }
        if (truncated) {
            builder.append("，已截断");
        }
        builder.append("\n\n").append(clip(content, MAX_CONTENT_CHARS));
        if (truncated) {
            builder.append("\n\n后续建议：如需更多内容，请提高 start_line 或按更小范围继续读取。");
        }
        return builder.toString();
    }

    static String directory(String skillName, String path, List<Map<String, Object>> entries, boolean truncated) {
        StringBuilder builder = new StringBuilder();
        builder.append("已列出 skill ").append(skillName).append(" 包内目录：").append(path).append('\n')
                .append("展示 ").append(entries.size()).append(" 项");
        if (truncated) {
            builder.append("，已截断");
        }
        builder.append("：\n\n");
        if (entries.isEmpty()) {
            builder.append("(目录为空)\n");
        }
        for (Map<String, Object> entry : entries) {
            String type = "directory".equals(value(entry.get("type"))) ? "[D]" : "[F]";
            builder.append(type).append(' ').append(value(entry.get("path")));
            Object size = entry.get("size");
            if (size != null) {
                builder.append(" (").append(size).append(" bytes)");
            }
            builder.append('\n');
        }
        if (truncated) {
            builder.append("\n后续建议：缩小 path 或 max_depth 后继续列出目录。");
        } else {
            builder.append("\n后续建议：读取 SKILL.md、references 下的资料，或用 grep 搜索关键词。");
        }
        return builder.toString();
    }

    static String glob(String skillName, String path, String pattern, List<String> matches, boolean truncated) {
        StringBuilder builder = new StringBuilder();
        builder.append("已在 skill ").append(skillName).append(" 中按 glob 查找文件。\n")
                .append("查找目录：").append(path).append('\n')
                .append("匹配模式：").append(pattern).append('\n')
                .append("命中 ").append(matches.size()).append(" 个文件");
        if (truncated) {
            builder.append("，已截断");
        }
        builder.append("：\n\n");
        if (matches.isEmpty()) {
            builder.append("(没有匹配文件)\n\n后续建议：调整 pattern，或先用 skill_list_directory_tool 查看目录结构。");
            return builder.toString();
        }
        for (String match : matches) {
            builder.append("- ").append(match).append('\n');
        }
        if (truncated) {
            builder.append("\n后续建议：缩小 path 或 pattern 后继续查找。");
        } else {
            builder.append("\n后续建议：用 skill_read_tool 读取最相关文件，或用 skill_grep_tool 在这些文件中搜索关键词。");
        }
        return builder.toString();
    }

    static String grep(String skillName, String path, String pattern, List<Map<String, Object>> matches,
                       boolean truncated) {
        StringBuilder builder = new StringBuilder();
        builder.append("已在 skill ").append(skillName).append(" 中搜索文本。\n")
                .append("搜索路径：").append(path).append('\n')
                .append("搜索词：").append(pattern).append('\n')
                .append("命中 ").append(matches.size()).append(" 条");
        if (truncated) {
            builder.append("，已截断");
        }
        builder.append("：\n\n");
        if (matches.isEmpty()) {
            builder.append("(没有匹配内容)\n\n后续建议：调整关键词、扩大 path，或先读取 SKILL.md 理解术语。");
            return builder.toString();
        }
        for (Map<String, Object> match : matches) {
            builder.append("- ")
                    .append(value(match.get("path")))
                    .append(':')
                    .append(value(match.get("line")))
                    .append(" - ")
                    .append(value(match.get("text")))
                    .append('\n');
        }
        if (truncated) {
            builder.append("\n后续建议：缩小 path 或使用更具体的 pattern。");
        } else {
            builder.append("\n后续建议：读取命中路径附近内容，或直接基于这些片段继续总结。");
        }
        return builder.toString();
    }

    static String scriptResult(SkillDefinition skill, SkillScriptDefinition script, PythonToolResponseEntity response,
                               Map<String, Object> structured, List<String> artifactRefs) {
        StringBuilder builder = new StringBuilder();
        builder.append(response.isSuccess() ? "脚本执行成功" : "脚本执行失败")
                .append("：skill=").append(skill.getName())
                .append(", script=").append(script.getScriptName())
                .append('\n');
        appendOptional(builder, "状态：", response.getStatus());
        appendOptional(builder, "运行时：", script.getRuntime());
        appendOptional(builder, "退出码：", value(structured.get("exitCode")));
        appendOptional(builder, "错误码：", response.getErrorCode());
        appendOptional(builder, "错误信息：", response.getErrorMessage());

        appendSection(builder, "summary", value(structured.get("summary")));
        appendSection(builder, "stdout 摘要", value(structured.get("stdout")));
        appendSection(builder, "stderr 摘要", value(structured.get("stderr")));

        if (!artifactRefs.isEmpty()) {
            builder.append("\n产物引用：\n");
            artifactRefs.forEach(ref -> builder.append("- ").append(ref).append('\n'));
        }
        Object structuredOutput = structured.get("structuredOutput");
        if (structuredOutput instanceof Map<?, ?> map && !map.isEmpty()) {
            builder.append("\n结构化输出摘要：").append(clip(String.valueOf(map), 1200)).append('\n');
        }
        if (!response.isSuccess()) {
            appendOptional(builder, "恢复建议：", value(structured.get("recoveryHint")));
        }
        return builder.toString();
    }

    private static void appendScriptSummary(StringBuilder builder, List<?> scripts) {
        for (int i = 0; i < Math.min(scripts.size(), 5); i++) {
            Object raw = scripts.get(i);
            if (raw instanceof Map<?, ?> script) {
                builder.append(i == 0 ? " " : ", ")
                        .append(value(script.get("scriptName")))
                        .append('[')
                        .append(value(script.get("runtime")))
                        .append(']');
            }
        }
        if (scripts.size() > 5) {
            builder.append(" ...");
        }
    }

    private static void appendStringList(StringBuilder builder, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append('\n').append(title).append("：\n");
        for (String value : values) {
            builder.append("- ").append(value).append('\n');
        }
    }

    private static void appendSection(StringBuilder builder, String title, String value) {
        if (!StringUtils.hasText(value) || "null".equals(value)) {
            return;
        }
        builder.append('\n').append(title).append("：\n")
                .append(clip(value, MAX_SCRIPT_OUTPUT_CHARS))
                .append('\n');
    }

    private static void appendOptional(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value) && !"null".equals(value)) {
            builder.append(label).append(value).append('\n');
        }
    }

    private static String clip(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...(已截断，原始字符数=" + value.length() + ")";
    }

    private static String value(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }
}
