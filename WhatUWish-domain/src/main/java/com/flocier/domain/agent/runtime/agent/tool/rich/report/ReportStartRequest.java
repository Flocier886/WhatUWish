package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import org.springframework.util.StringUtils;

/** Report 工具的受控启动请求；内部存储定位在序列化时才附入 Python 私有请求。 */
@Value
@Builder
public class ReportStartRequest {
    String task;
    List<String> inputArtifactIds;
    String outputFileName;
    String outputType;
    String executionId;
    List<ReportInputArtifactDescriptor> inputArtifacts;

    public static ReportStartRequest from(ToolInvokeRequest request, String executionId) {
        Map<String, Object> arguments = request == null || request.getArguments() == null
                ? Map.of() : request.getArguments();
        String task = normalized(arguments.get("task"));
        if (!StringUtils.hasText(task)) {
            throw new ReportStreamException("report_request_invalid", "report_tool 需要非空 task 参数");
        }
        List<String> artifactIds = stringList(arguments.get("inputArtifactIds"));
        if (artifactIds.size() > 12 || artifactIds.size() != artifactIds.stream().distinct().count()) {
            throw new ReportStreamException("report_request_invalid", "inputArtifactIds 数量或唯一性不符合约束");
        }
        String outputType = normalized(arguments.get("outputType"));
        if (!List.of("markdown", "html", "ppt").contains(outputType)) {
            throw new ReportStreamException("report_request_invalid", "outputType 仅支持 markdown、html 或 ppt");
        }
        String outputFileName = normalized(arguments.get("outputFileName"));
        if (!StringUtils.hasText(outputFileName)) {
            outputFileName = "report";
        }
        if (!StringUtils.hasText(executionId)) {
            throw new ReportStreamException("report_request_invalid", "Report 执行标识不能为空");
        }
        return ReportStartRequest.builder()
                .task(task)
                .inputArtifactIds(List.copyOf(artifactIds))
                .outputFileName(outputFileName)
                .outputType(outputType)
                .executionId(executionId)
                .inputArtifacts(List.of())
                .build();
    }

    public ReportStartRequest withResolvedInputs(List<ReportInputArtifactDescriptor> resolved) {
        if (resolved == null || resolved.size() != inputArtifactIds.size()) {
            throw new ReportStreamException("report_input_invalid", "Report 输入 Artifact 解析结果不完整");
        }
        return ReportStartRequest.builder()
                .task(task)
                .inputArtifactIds(inputArtifactIds)
                .outputFileName(outputFileName)
                .outputType(outputType)
                .executionId(executionId)
                .inputArtifacts(List.copyOf(resolved))
                .build();
    }

    public ReportStartRequest withInputArtifactIds(List<String> artifactIds) {
        if (artifactIds == null || artifactIds.isEmpty() || artifactIds.size() > 12
                || artifactIds.size() != artifactIds.stream().distinct().count()) {
            throw new ReportStreamException("report_input_missing", "当前任务没有可供 Report 使用的研究材料。");
        }
        return ReportStartRequest.builder()
                .task(task)
                .inputArtifactIds(List.copyOf(artifactIds))
                .outputFileName(outputFileName)
                .outputType(outputType)
                .executionId(executionId)
                .inputArtifacts(List.of())
                .build();
    }

    /** 仅作为 Java -> Python 的私有 JSON 载荷，绝不能复用到 observation、Ledger 或 Live DTO。 */
    public Map<String, Object> toPythonPayload() {
        if (inputArtifacts == null || inputArtifacts.isEmpty()) {
            throw new ReportStreamException("report_input_invalid", "Report 输入 Artifact 尚未授权解析");
        }
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (ReportInputArtifactDescriptor artifact : inputArtifacts) {
            artifacts.add(Map.of(
                    "artifactId", artifact.getArtifactId(),
                    "fileName", artifact.getFileName(),
                    "mimeType", artifact.getMimeType(),
                    "fileSize", artifact.getFileSize(),
                    "storageType", artifact.getStorageType(),
                    "storageKey", artifact.getStorageKey()
            ));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", task);
        payload.put("inputArtifacts", artifacts);
        payload.put("outputFileName", outputFileName);
        payload.put("outputType", outputType);
        payload.put("executionId", executionId);
        return payload;
    }

    private static String normalized(Object value) {
        String raw = value == null ? "" : String.valueOf(value);
        String normalized = raw.trim().replaceAll("\\s+", " ");
        return normalized.length() > 1200 ? normalized.substring(0, 1200) : normalized;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String normalized = normalized(item);
            if (!StringUtils.hasText(normalized) || normalized.length() > 80) {
                throw new ReportStreamException("report_request_invalid", "inputArtifactIds 包含无效标识");
            }
            result.add(normalized);
        }
        return result;
    }
}
