package com.flocier.domain.agent.service.rag.retrieval;

import lombok.Builder;
import lombok.Getter;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 增强 RAG 检索的受控阶段结果。
 *
 * <p>它只表达检索事实，不承担旧 Advisor 的 Prompt 注入，也不决定 ReAct Tool 的 observation 文案。</p>
 */
@Getter
@Builder
public class EnhancedRagRetrievalResult {

    private final String originalQuery;
    private final String rewrittenQuery;
    private final String knowledgeTag;
    private final boolean vectorAvailable;
    private final String vectorFailureMessage;
    @Builder.Default
    private final List<Document> vectorDocuments = List.of();
    @Builder.Default
    private final List<Document> keywordDocuments = List.of();
    @Builder.Default
    private final List<Document> coarseRankDocuments = List.of();
    @Builder.Default
    private final List<Document> rerankDocuments = List.of();
    @Builder.Default
    private final Map<String, Double> rerankScores = Map.of();
    @Builder.Default
    private final List<Document> finalDocuments = List.of();
    /**
     * 受控阶段信息：供旧 Advisor 延续 qa_* metadata，或供 Tool 形成结构化诊断。
     */
    @Builder.Default
    private final Map<String, Object> diagnostics = new LinkedHashMap<>();
    @Builder.Default
    private final List<String> degradations = List.of();
}
