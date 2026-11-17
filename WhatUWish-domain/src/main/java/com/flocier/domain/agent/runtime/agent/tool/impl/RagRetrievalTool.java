package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetrievalResult;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新 ReAct Runtime 的正式增强知识库检索工具。
 *
 * <p>工具只负责受控参数与 observation 投影，完整检索主链由
 * {@link EnhancedRagRetriever} 统一提供给旧 Advisor 和新 Runtime。</p>
 */
@RequiredArgsConstructor
public class RagRetrievalTool implements BaseTool {

    private static final int MAX_QUERY_LENGTH = 1200;
    private static final int MAX_TOP_K = 8;
    private static final int MAX_EXCERPT_LENGTH = 420;
    private static final int MAX_OBSERVATION_LENGTH = 4800;

    private final String name;
    private final String description;
    private final Object params;
    private final EnhancedRagRetriever enhancedRagRetriever;
    private final int defaultTopK;

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Object params() {
        return params;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request)) {
            return ToolResultPayload.failure("rag_invalid_argument", "RAG 工具调用参数无效。", "知识库检索失败：调用参数无效。");
        }
        try {
            Arguments arguments = parseArguments(request.getArguments());
            EnhancedRagRetrievalResult result = enhancedRagRetriever.retrieve(
                    arguments.query(), arguments.knowledgeTag(), arguments.topK(), new LinkedHashMap<>());
            if (!result.isVectorAvailable()) {
                return ToolResultPayload.builder()
                        .success(false)
                        .status("failed")
                        .errorCode("rag_vector_unavailable")
                        .errorMessage(result.getVectorFailureMessage())
                        .toolResult("知识库基础检索不可用。")
                        .llmObservation("知识库检索未获得可信证据：基础向量检索当前不可用。不要编造知识库内容，可说明限制或选择其他合适工具。")
                        .structuredOutput(structured(result, List.of()))
                        .build();
            }
            List<Map<String, Object>> documents = projectDocuments(result.getFinalDocuments());
            String observation = observation(result, documents);
            return ToolResultPayload.builder()
                    .success(true)
                    .status("success")
                    .toolResult(observation)
                    .llmObservation(observation)
                    .structuredOutput(structured(result, documents))
                    .build();
        } catch (IllegalArgumentException exception) {
            return ToolResultPayload.failure("rag_invalid_argument", exception.getMessage(), "知识库检索失败：" + exception.getMessage());
        } catch (Exception exception) {
            String message = StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
            return ToolResultPayload.failure("rag_retrieval_failed", message, "知识库检索失败，未获得可信证据。可说明限制或选择其他合适工具。");
        }
    }

    private Arguments parseArguments(Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String query = text(arguments.get("query"));
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("必须提供要检索的 query。");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query 过长，最多 " + MAX_QUERY_LENGTH + " 个字符。");
        }
        String knowledgeTag = text(arguments.get("knowledgeTag"));
        if (StringUtils.hasText(knowledgeTag) && (knowledgeTag.length() > 128 || knowledgeTag.contains("'") || knowledgeTag.contains("\""))) {
            throw new IllegalArgumentException("knowledgeTag 格式无效。");
        }
        int topK = integer(arguments.get("topK"), defaultTopK);
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK 必须位于 1 到 " + MAX_TOP_K + " 之间。");
        }
        return new Arguments(query, knowledgeTag, topK);
    }

    private List<Map<String, Object>> projectDocuments(List<Document> source) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Document document = source.get(index);
            if (document == null || !StringUtils.hasText(document.getText())) {
                continue;
            }
            Map<String, Object> metadata = document.getMetadata() == null ? Map.of() : document.getMetadata();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", index + 1);
            putText(item, "knowledgeTag", firstText(metadata, "knowledge", "knowledge_tag"));
            putText(item, "source", firstText(metadata, "source_file", "source", "title", "doc_id"));
            item.put("excerpt", excerpt(document.getText()));
            documents.add(item);
        }
        return documents;
    }

    private String observation(EnhancedRagRetrievalResult result, List<Map<String, Object>> documents) {
        if (documents.isEmpty()) {
            return "知识库检索已完成，但当前知识库未检索到足够相关资料。不要把未召回内容当作内部事实；可明确说明资料不足，或在任务需要时使用其他合适工具。";
        }
        StringBuilder builder = new StringBuilder("知识库增强检索完成：最终命中 ")
                .append(documents.size()).append(" 条可用资料。\n");
        for (Map<String, Object> document : documents) {
            builder.append("[").append(document.get("rank")).append("]");
            if (document.get("knowledgeTag") != null) {
                builder.append(" 知识库：").append(document.get("knowledgeTag"));
            }
            if (document.get("source") != null) {
                builder.append("；来源：").append(document.get("source"));
            }
            builder.append("\n摘要：").append(document.get("excerpt")).append("\n");
        }
        if (!result.getDegradations().isEmpty()) {
            builder.append("检索已启用可用结果降级：").append(String.join("、", result.getDegradations())).append("。\n");
        }
        builder.append("请仅依据以上已召回资料回答；资料不足时需明确说明。");
        return truncate(builder.toString(), MAX_OBSERVATION_LENGTH);
    }

    private Map<String, Object> structured(EnhancedRagRetrievalResult result, List<Map<String, Object>> documents) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("originalQuery", result.getOriginalQuery());
        output.put("rewrittenQuery", result.getRewrittenQuery());
        if (StringUtils.hasText(result.getKnowledgeTag())) {
            output.put("knowledgeTag", result.getKnowledgeTag());
        }
        output.put("vectorHitCount", result.getVectorDocuments().size());
        output.put("bm25HitCount", result.getKeywordDocuments().size());
        output.put("rrfHitCount", result.getCoarseRankDocuments().size());
        output.put("rerankHitCount", result.getRerankDocuments().size());
        output.put("finalHitCount", documents.size());
        output.put("degradations", result.getDegradations());
        output.put("documents", documents);
        return output;
    }

    private int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("topK 必须是整数。");
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstText(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            String value = text(metadata.get(key));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, truncate(value, 180));
        }
    }

    private String excerpt(String content) {
        return truncate(content.replaceAll("\\s+", " ").trim(), MAX_EXCERPT_LENGTH);
    }

    private String truncate(String value, int limit) {
        if (!StringUtils.hasText(value) || value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private record Arguments(String query, String knowledgeTag, int topK) {
    }
}
