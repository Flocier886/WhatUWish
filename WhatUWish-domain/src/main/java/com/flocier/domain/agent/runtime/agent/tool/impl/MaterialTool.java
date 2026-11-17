package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import com.flocier.domain.agent.runtime.file.WorkspaceOutputPersistence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 在当前会话工作区执行材料工具，并交付本轮生成的文件。 */
public class MaterialTool implements BaseTool {
    private static final Logger log = LoggerFactory.getLogger(MaterialTool.class);
    private static final java.util.Set<String> NAMES = java.util.Set.of("pdf_reader", "pdf_structure", "citation_extractor",
            "word_reader", "excel_reader", "csv_processor", "html_processor", "markdown_processor", "text_processor",
            "image_ocr", "data_clean", "data_merge", "data_transform", "data_aggregate", "data_validate", "sql_query");

    public static boolean isMaterialName(String name) { return NAMES.contains(name); }
    private final String name;
    private final String description;
    private final Object params;
    private final String pythonService;
    private final PythonToolClient client;
    private final MaterialFileAccessResolver access;
    private final WorkspaceOutputPersistence outputs;

    public MaterialTool(String name, String description, Object params, String pythonService,
                        PythonToolClient client, MaterialFileAccessResolver access, ArtifactContentWriter writer,
                        ArtifactContentRemover remover, String writeStorageType, String writePrefix) {
        this.name = name;
        this.description = description;
        this.params = params;
        this.pythonService = pythonService;
        this.client = client;
        this.access = access;
        this.outputs = new WorkspaceOutputPersistence(writer, remover, writeStorageType, writePrefix);
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Object params() { return params; }

    @Override public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request))
            return ToolResultPayload.failure("material_invalid_request", "材料工具调用参数无效", "材料工具调用参数无效");
        try {
            MaterialFileAccessResolver.Prepared prepared = access.prepare(context, name, request.getArguments());
            PythonToolResponseEntity response = invokePython(request, context, prepared);
            ensureNotInterrupted();
            if (response == null || (!response.isSuccess() && !"code_execution".equals(name))) {
                String message = response == null || response.getErrorMessage() == null
                        ? "材料处理未返回结果" : response.getErrorMessage();
                return ToolResultPayload.failure(response == null || response.getErrorCode() == null
                        ? "material_failed" : response.getErrorCode(), message, message);
            }
            if (response.getArtifacts() != null && !response.getArtifacts().isEmpty())
                throw new IllegalStateException("材料服务返回了不受支持的直接产物");
            List<Map<String, Object>> artifacts = outputs.persist(response.getMaterialOutputs() == null
                    ? List.of() : response.getMaterialOutputs(), prepared.workspaceRoot(), name);
            return ToolResultPayload.builder()
                    .success(response.isSuccess())
                    .status(response.getStatus() == null ? (response.isSuccess() ? "success" : "failed") : response.getStatus())
                    .errorCode(response.getErrorCode())
                    .errorMessage(response.getErrorMessage())
                    .toolResult(response.getOutput())
                    .llmObservation(response.getLlmObservation())
                    .structuredOutput(response.getStructured() == null ? Map.of() : new LinkedHashMap<>(response.getStructured()))
                    .ledgerFacts(response.getLedgerFacts() == null ? List.of() : response.getLedgerFacts())
                    .artifactDescriptors(artifacts)
                    .artifactRefs(artifacts.stream().map(item -> String.valueOf(item.get("artifactId"))).toList())
                    .build();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return ToolResultPayload.failure("material_interrupted", "材料处理已中断", "材料处理已中断，未交付本次产物。");
        } catch (IllegalArgumentException failure) {
            return ToolResultPayload.failure("material_access_denied", failure.getMessage(), "材料访问失败：" + failure.getMessage());
        } catch (Exception failure) {
            log.error("材料工具执行失败，toolName={}", name, failure);
            return ToolResultPayload.failure("material_output_failed", "材料输出持久化失败",
                    "材料输出持久化失败，本次结果不可作为已交付文件使用。");
        }
    }

    protected PythonToolResponseEntity invokePython(ToolInvokeRequest request, AgentContext context,
                                                    MaterialFileAccessResolver.Prepared prepared) {
        return client.executeMaterial(pythonService, name, request, context, prepared);
    }

    private void ensureNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("材料处理已中断");
    }
}
