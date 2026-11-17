package com.flocier.domain.agent.runtime.agent.tool.docgen;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.MaterialTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolClient;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把 master 的文件名参数映射为本站会话工作区输出路径。 */
public class DocGenMaterialTool extends MaterialTool {
    public DocGenMaterialTool(AbstractDocGenTool contract, String pythonService, PythonToolClient client,
                              MaterialFileAccessResolver access, ArtifactContentWriter writer,
                              ArtifactContentRemover remover, String writeStorageType, String writePrefix) {
        super(contract.getName(), contract.description(), contract.params(), pythonService, client,
                access, writer, remover, writeStorageType, writePrefix);
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request))
            return ToolResultPayload.failure("docgen_invalid_request", "文档工具参数无效", "文档工具参数无效");
        Map<String, Object> args = new LinkedHashMap<>(request.getArguments() == null ? Map.of() : request.getArguments());
        Object supplied = args.get("output_path");
        if (supplied == null) supplied = args.get("fileName");
        if (supplied != null && (!(supplied instanceof String text) || !safeFileName(text)))
            return ToolResultPayload.failure("docgen_output_path_invalid", "输出文件名无效",
                    "输出文件名必须是当前工作区 outputs/ 下的文件名。");
        if (supplied instanceof String text) {
            String normalized = text.startsWith("outputs/") ? text : "outputs/" + text;
            args.put("output_path", normalized);
        }
        ToolInvokeRequest adapted = ToolInvokeRequest.builder().toolName(request.getToolName())
                .toolCallId(request.getToolCallId()).arguments(args).metadata(request.getMetadata())
                .sourceHint(request.getSourceHint()).requestId(request.getRequestId())
                .sessionId(request.getSessionId()).step(request.getStep()).build();
        return super.execute(adapted, context);
    }

    private boolean safeFileName(String value) {
        String name = value.startsWith("outputs/") ? value.substring("outputs/".length()) : value;
        return !name.isBlank() && name.length() <= 160 && !name.contains("/") && !name.contains("\\")
                && !name.contains("..") && !name.contains(":") && !name.equals(".");
    }
}
