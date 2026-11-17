package com.flocier.domain.agent.service.armory.support;

import com.flocier.domain.agent.model.vo.AiQueryRewriterTypeEnumVO;
import com.flocier.domain.agent.model.vo.AiRerankerTypeEnumVO;
import com.flocier.domain.agent.model.vo.AiKeywordRetrieverTypeEnumVO;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import com.flocier.domain.agent.service.armory.node.factory.element.keywordRetriever.EsBm25KeywordRetriever;
import com.flocier.domain.agent.service.armory.node.factory.element.queryRewriter.OpenAiQueryRewriter;
import com.flocier.domain.agent.service.armory.node.factory.element.rerank.BgeRerankerV2M3Client;
import org.springframework.stereotype.Component;

/**
 * 按 {@link AiQueryRewriterTypeEnumVO} / {@link AiKeywordRetrieverTypeEnumVO} /
 * {@link AiRerankerTypeEnumVO} 的 code 返回 IoC 中的单例实现。
 */
@Component
public class DefaultRagAdvanceAdvisorBeansProvider implements RagAdvanceAdvisorBeansProvider {

    private final OpenAiQueryRewriter openAiQueryRewriter;
    private final EsBm25KeywordRetriever esBm25KeywordRetriever;
    private final BgeRerankerV2M3Client bgeRerankerV2M3Client;

    public DefaultRagAdvanceAdvisorBeansProvider(OpenAiQueryRewriter openAiQueryRewriter,
                                                 EsBm25KeywordRetriever esBm25KeywordRetriever,
                                                 BgeRerankerV2M3Client bgeRerankerV2M3Client) {
        this.openAiQueryRewriter = openAiQueryRewriter;
        this.esBm25KeywordRetriever = esBm25KeywordRetriever;
        this.bgeRerankerV2M3Client = bgeRerankerV2M3Client;
    }

    @Override
    public EnhancedRagRetriever.QueryRewriter getQueryRewriter(String typeCode) {
        return switch (AiQueryRewriterTypeEnumVO.getByCode(typeCode.trim())) {
            case OPENAI_QUERY_REWRITER -> openAiQueryRewriter;
        };
    }

    @Override
    public EnhancedRagRetriever.KeywordRetriever getKeywordRetriever(String typeCode) {
        return switch (AiKeywordRetrieverTypeEnumVO.getByCode(typeCode.trim())) {
            case ES_BM25_KEYWORD_RETRIEVER -> esBm25KeywordRetriever;
        };
    }

    @Override
    public EnhancedRagRetriever.Reranker getReranker(String typeCode) {
        return switch (AiRerankerTypeEnumVO.getByCode(typeCode.trim())) {
            case BGE_RERANKER_V2_M3_CLIENT -> bgeRerankerV2M3Client;
        };
    }
}
