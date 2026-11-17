package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** master CodeExecutionTool 的模型合同，执行和产物登记复用本站材料桥。 */
public class CodeExecutionTool extends MaterialTool {
    private static final String DESCRIPTION = "在受控 Python 沙箱执行一次完整源码，用于计算、分析 CSV/Excel、制图和生成文件。"
            + "当前目录是会话工作区；先用 workspace_list 查找已授权的 inputs/ 文件，使用相对路径读取。"
            + "需要分析 web_fetch 或 DeepSearch 已保存的来源时，将返回的 Artifact ID 放入 sourceArtifactIds。"
            + "同次调用的源码按 inputs/artifacts/<ID>-web-page.txt 或 inputs/artifacts/<ID>-deepsearch-source-package.md 读取所选来源。"
            + "生成文件必须写在 outputs/ 下，例如 df.to_csv('outputs/result.csv', index=False) "
            + "或 plt.savefig('outputs/chart.png')。本次新增或修改的有效输出会自动登记为可下载 Artifact。"
            + "不要指定宿主绝对路径、URL 或存储定位信息。执行失败会返回 stdout、stderr 和错误原因。";

    public CodeExecutionTool(String pythonService, PythonToolClient client, MaterialFileAccessResolver access,
                             ArtifactContentWriter writer, ArtifactContentRemover remover,
                             String writeStorageType, String writePrefix) {
        super("code_execution", DESCRIPTION, paramsDefinition(), pythonService, client, access,
                writer, remover, writeStorageType, writePrefix);
    }

    private static Map<String, Object> paramsDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source", Map.of("type", "string", "description",
                "完整 Python 源码。cwd 是当前会话工作区；读取 inputs/，仅向 outputs/ 写文件。"));
        properties.put("inputs", Map.of("type", "object", "description", "可选：注入 Python 全局变量的 JSON 对象",
                "additionalProperties", true));
        properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 120,
                "description", "本次代码执行秒数上限，默认 120 秒"));
        properties.put("sourceArtifactIds", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "可选：web_fetch 网页或 DeepSearch 来源包的 Artifact ID；服务端会放入 inputs/artifacts/"));
        return Map.of("type", "object", "properties", properties, "required", List.of("source"),
                "additionalProperties", false);
    }
}
