package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Python `deep_search_result.v1` 的 Java 读取模型，不包含 Java Ledger 或前端字段。 */
@Data
@NoArgsConstructor
public class DeepSearchFinalResult {

    private static final int SUMMARY_LIMIT = 1600;
    private static final int PRESENTATION_SUMMARY_LIMIT = 1200;
    private static final int LIMITATION_LIMIT = 400;
    private static final int STAGE_SUMMARY_LIMIT = 400;
    private static final int MAX_STAGE_SUMMARIES = 20;

    private String schemaVersion;
    private String executionId;
    private String researchExecutionId;
    private String status;
    /** Python 明确的终态处置：不要再由 Java 从 partial_success 猜测是否应重调。 */
    private String deliveryDisposition;
    /** 证据是否充分与工作流是否已交付分开表达，仅用于指导 ReAct 如何使用现有 artifact。 */
    private String researchOutcome;
    /** 仅在无法交付 artifact 时请求调用方处理的外部前提。 */
    private String callerAction;
    /** 只由规范化研究范围计算，用于同一 Run 内的语义防重。 */
    private String researchScopeFingerprint;
    /** 最终研究证据包的生成方式，例如模型综合或确定性 evidence-only。 */
    private String deliveryMode;
    private String summary;
    private String presentationSummary;
    private List<Map<String, Object>> sources = new ArrayList<>();
    private List<Map<String, Object>> evidence = new ArrayList<>();
    private Map<String, Object> coverageReview;
    private List<String> limitations = new ArrayList<>();
    private String stopReason;
    private List<Map<String, Object>> stageSummaries = new ArrayList<>();
    private List<ArtifactRuntimeDescriptor> artifacts = new ArrayList<>();

    /** 仅返回可供 Java 工具账本保存的受控结果摘要，不保留 Python artifact 的本地物理地址。 */
    public Map<String, Object> toControlledSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("executionId", executionId);
        result.put("researchExecutionId", researchExecutionId);
        result.put("status", status);
        result.put("deliveryDisposition", deliveryDisposition);
        result.put("researchOutcome", researchOutcome);
        result.put("callerAction", callerAction);
        result.put("researchScopeFingerprint", researchScopeFingerprint);
        result.put("deliveryMode", deliveryMode);
        result.put("summary", limit(summary, SUMMARY_LIMIT));
        result.put("presentationSummary", limit(presentationSummary, PRESENTATION_SUMMARY_LIMIT));
        result.put("sourceCount", sources == null ? 0 : sources.size());
        result.put("evidenceCount", evidence == null ? 0 : evidence.size());
        result.put("limitations", controlledLimitations());
        result.put("stopReason", limit(stopReason, LIMITATION_LIMIT));
        result.put("stageSummaries", controlledStageSummaries());
        List<Map<String, Object>> artifactRefs = new ArrayList<>();
        if (artifacts != null) {
            for (ArtifactRuntimeDescriptor artifact : artifacts) {
                if (artifact == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("artifactId", artifact.getArtifactId());
                item.put("name", limit(artifact.getName(), LIMITATION_LIMIT));
                item.put("artifactType", limit(artifact.getArtifactType(), LIMITATION_LIMIT));
                item.put("fileName", limit(artifact.getFileName(), LIMITATION_LIMIT));
                item.put("contentSummary", limit(artifact.getContentSummary(), LIMITATION_LIMIT));
                item.put("mimeType", limit(artifact.getMimeType(), LIMITATION_LIMIT));
                item.put("fileSize", artifact.getFileSize());
                artifactRefs.add(item);
            }
        }
        result.put("artifacts", artifactRefs);
        return result;
    }

    private List<String> controlledLimitations() {
        if (limitations == null || limitations.isEmpty()) {
            return List.of();
        }
        return limitations.stream().filter(java.util.Objects::nonNull).limit(10)
                .map(item -> limit(item, LIMITATION_LIMIT)).toList();
    }

    private List<Map<String, Object>> controlledStageSummaries() {
        if (stageSummaries == null || stageSummaries.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> stage : stageSummaries) {
            if (stage == null || stage.isEmpty() || result.size() >= MAX_STAGE_SUMMARIES) {
                continue;
            }
            Map<String, Object> controlled = new LinkedHashMap<>();
            copyStageField(stage, controlled, "phase");
            copyStageField(stage, controlled, "event");
            copyStageField(stage, controlled, "status");
            copyStageField(stage, controlled, "summary");
            copyStageField(stage, controlled, "message");
            copyStageField(stage, controlled, "count");
            copyStageField(stage, controlled, "timestamp");
            if (!controlled.isEmpty()) {
                result.add(controlled);
            }
        }
        return List.copyOf(result);
    }

    private void copyStageField(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value instanceof String text ? limit(text, STAGE_SUMMARY_LIMIT) : value);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated, total=" + value.length() + ")";
    }
}
