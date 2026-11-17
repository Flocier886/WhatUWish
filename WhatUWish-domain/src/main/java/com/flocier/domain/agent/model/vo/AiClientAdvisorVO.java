package com.flocier.domain.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientAdvisorVO {

    /**
     * 顾问ID
     */
    private String advisorId;

    /**
     * 顾问名称
     */
    private String advisorName;

    /**
     * 顾问类型(ChatMemory/RagAnswer/RagAnswerAdvance 等，与 {@link AiClientAdvisorTypeEnumVO#getCode()} 一致)
     */
    private String advisorType;

    /**
     * 顺序号
     */
    private Integer orderNum;

    /**
     * 扩展；记忆
     */
    private ChatMemory chatMemory;

    /**
     * 扩展；rag 问答（仅向量召回）
     */
    private RagAnswer ragAnswer;

    /**
     * 扩展；高级 rag（query 改写 + 可选关键词/BM25 + 可选 rerank；BM25 实现类接入前可将 keywordTopK 置 0）
     */
    private RagAnswerAdvance ragAnswerAdvance;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChatMemory {
        private int maxMessages;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RagAnswer {
        private int topK = 4;
        private String filterExpression;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RagAnswerAdvance {
        /**
         * 向量召回 topK（对应 {@link org.springframework.ai.vectorstore.SearchRequest}）
         */
        private int topK = 4;
        private String filterExpression;

        /**
         * 关键词/BM25 路召回条数；为 0 或未注入 {@link #keywordRetrieverType} 对应实现时不生效
         */
        private int keywordTopK = 0;

        /**
         * 关键词/BM25 检索实现类型编码，对应 {@link AiKeywordRetrieverTypeEnumVO#getCode()}；空表示不启用关键词召回
         */
        private String keywordRetrieverType;

        /**
         * Query 改写实现类型编码，对应 {@link AiQueryRewriterTypeEnumVO#getCode()}；空表示不改写
         */
        private String queryRewriterType;

        /**
         * Rerank 实现类型编码，对应 {@link AiRerankerTypeEnumVO#getCode()}；空表示不精排
         */
        private String rerankerType;

        /**
         * 精排后写入上下文的前 K 条文档
         */
        private int rerankTopK = 4;
    }

}
