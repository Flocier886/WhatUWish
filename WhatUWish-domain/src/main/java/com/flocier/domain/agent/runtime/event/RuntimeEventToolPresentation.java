package com.flocier.domain.agent.runtime.event;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * Runtime Event 面向用户的工具文本投影。
 *
 * <p>工具的 observation 是 Agent 决策上下文，可以包含内部 Artifact 定位信息和受限正文；
 * Runtime Event 与 History Replay 不是该上下文的镜像，只能展示受控摘要。</p>
 */
public final class RuntimeEventToolPresentation {

    private static final String ARTIFACT_READ_TOOL = "artifact_read";
    private static final String ARTIFACT_READ_ARGUMENTS = "Artifact 的内部读取定位参数不在运行展示中提供。";
    private static final String ARTIFACT_READ_OBSERVATION = "Agent 已分段读取既有 Artifact；正文仅用于当前运行决策，不在运行记录中展示。";
    private static final String SENSITIVE_ARGUMENTS = "工具参数可能包含正文或本地定位信息，不在运行展示中提供。";
    private static final int MAX_OBSERVATION_LENGTH = 1_200;
    private static final Pattern STORAGE_REFERENCE_LINE = Pattern.compile(
            "(?im)^.*(?:storageType|storageKey|storageRef|storage_type|storage_key|storage_ref).*(?:\\R|$)"
    );
    private static final Pattern STORAGE_REFERENCE_CALL = Pattern.compile(
            "(?i)artifact_read\\(\\s*(?:storageType|storageKey|storageRef|storage_type|storage_key|storage_ref)\\s*=\\s*[^)]*\\)"
    );

    private RuntimeEventToolPresentation() {
    }

    public static String arguments(String toolName, String arguments) {
        if ("emit_ui_tree".equals(toolName)) return "发布交互画布";
        if ("emit_ui_patch".equals(toolName)) return "更新交互画布";
        if (ARTIFACT_READ_TOOL.equals(toolName)) {
            return ARTIFACT_READ_ARGUMENTS;
        }
        if (containsArtifactBodyOrLocalWriteArguments(toolName)) {
            return SENSITIVE_ARGUMENTS;
        }
        return redactStorageReferences(arguments);
    }

    public static String observation(String toolName, String observation) {
        if (ARTIFACT_READ_TOOL.equals(toolName)) {
            return ARTIFACT_READ_OBSERVATION;
        }
        return limit(redactStorageReferences(observation));
    }

    public static String errorMessage(String errorMessage) {
        return redactStorageReferences(errorMessage);
    }

    public static String summary(String summary) {
        return redactStorageReferences(summary);
    }

    private static String redactStorageReferences(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String withoutLines = STORAGE_REFERENCE_LINE.matcher(value).replaceAll("");
        return STORAGE_REFERENCE_CALL.matcher(withoutLines).replaceAll("artifact_read(artifactId=已关联产物)").trim();
    }

    private static boolean containsArtifactBodyOrLocalWriteArguments(String toolName) {
        return "artifact_save".equals(toolName)
                || "file_write".equals(toolName)
                || "skill_script_runner".equals(toolName);
    }

    private static String limit(String value) {
        if (!StringUtils.hasText(value) || value.length() <= MAX_OBSERVATION_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_OBSERVATION_LENGTH) + "\n[运行展示已截断]";
    }
}
