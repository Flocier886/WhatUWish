package com.flocier.domain.agent.service.armory.support;

import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;

/**
 * 将配置中的 {@code queryRewriterType} / {@code keywordRetrieverType} / {@code rerankerType}
 * 解析为 Spring 容器内已注入的 Bean，
 * 以便 {@link com.flocier.domain.agent.service.armory.node.factory.element.queryRewriter.OpenAiQueryRewriter}、
 * {@link com.flocier.domain.agent.service.armory.node.factory.element.rerank.BgeRerankerV2M3Client}
 * 等实现上的 {@code @Value} 生效。
 */
public interface RagAdvanceAdvisorBeansProvider {

    EnhancedRagRetriever.QueryRewriter getQueryRewriter(String typeCode);

    EnhancedRagRetriever.KeywordRetriever getKeywordRetriever(String typeCode);

    EnhancedRagRetriever.Reranker getReranker(String typeCode);
}
