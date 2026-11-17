package com.flocier.domain.agent.service.armory.node.factory.element;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetrievalResult;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 旧 Flow 的高级 RAG Advisor。
 *
 * <p>增强检索主链已经由 {@link EnhancedRagRetriever} 共用；该类只保留旧 ChatClient
 * 的 Prompt Context 注入与 qa_* metadata 兼容投影。</p>
 */
public class RagAnswerAdvanceAdvisor implements BaseAdvisor {

    private final EnhancedRagRetriever enhancedRagRetriever;
    private final String userTextAdvise;

    public RagAnswerAdvanceAdvisor(VectorStore vectorStore,
                                   SearchRequest vectorSearchRequest,
                                   EnhancedRagRetriever.KeywordRetriever keywordRetriever,
                                   int keywordTopK,
                                   EnhancedRagRetriever.QueryRewriter queryRewriter,
                                   EnhancedRagRetriever.Reranker reranker,
                                   int rerankTopK) {
        this(new EnhancedRagRetriever(vectorStore, vectorSearchRequest, keywordRetriever, keywordTopK,
                queryRewriter, reranker, rerankTopK));
    }

    public RagAnswerAdvanceAdvisor(EnhancedRagRetriever enhancedRagRetriever) {
        this.enhancedRagRetriever = Objects.requireNonNull(enhancedRagRetriever, "enhancedRagRetriever must not be null");
        this.userTextAdvise = "\nContext information is below, surrounded by ---------------------\n\n---------------------\n"
                + "{question_answer_context}\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\n"
                + "reply to the user comment. If the answer is not in the context, inform\nthe user that you can't answer the question.\n";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Map<String, Object> context = new HashMap<>(request.context());
        String userText = request.prompt().getUserMessage().getText();
        EnhancedRagRetrievalResult retrieval = enhancedRagRetriever.retrieve(userText, null, context);
        projectLegacyMetadata(context, retrieval);

        String documentContext = retrieval.getFinalDocuments().stream()
                .map(Document::getText)
                .filter(org.springframework.util.StringUtils::hasText)
                .collect(Collectors.joining(System.lineSeparator()));
        Map<String, Object> advisedParams = new HashMap<>(context);
        advisedParams.put("question_answer_context", documentContext);
        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(
                        new UserMessage(userText + System.lineSeparator() + userTextAdvise),
                        new AssistantMessage(JSON.toJSONString(advisedParams))
                ).build())
                .context(advisedParams)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        ChatResponse.Builder builder = ChatResponse.builder().from(response.chatResponse());
        copyMetadata(builder, response.context(), "qa_original_query");
        copyMetadata(builder, response.context(), "qa_rewritten_query");
        copyMetadata(builder, response.context(), "qa_vector_retrieved_documents");
        copyMetadata(builder, response.context(), "qa_keyword_retrieved_documents");
        copyMetadata(builder, response.context(), "qa_coarse_rank_documents");
        copyMetadata(builder, response.context(), "qa_rerank_documents");
        copyMetadata(builder, response.context(), "qa_rerank_scores");
        copyMetadata(builder, response.context(), "qa_retrieved_documents");
        return ChatClientResponse.builder().chatResponse(builder.build()).context(response.context()).build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return after(chain.nextCall(before(request, chain)), chain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return BaseAdvisor.super.adviseStream(request, chain);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    private void projectLegacyMetadata(Map<String, Object> context, EnhancedRagRetrievalResult retrieval) {
        context.put("qa_original_query", retrieval.getOriginalQuery());
        context.put("qa_rewritten_query", retrieval.getRewrittenQuery());
        context.put("qa_vector_retrieved_documents", retrieval.getVectorDocuments());
        context.put("qa_keyword_retrieved_documents", retrieval.getKeywordDocuments());
        context.put("qa_coarse_rank_documents", retrieval.getCoarseRankDocuments());
        if (!retrieval.getRerankDocuments().isEmpty()) {
            context.put("qa_rerank_documents", retrieval.getRerankDocuments());
            context.put("qa_rerank_scores", retrieval.getRerankScores());
        }
        context.put("qa_retrieved_documents", retrieval.getFinalDocuments());
        retrieval.getDiagnostics().forEach(context::putIfAbsent);
    }

    private void copyMetadata(ChatResponse.Builder builder, Map<String, Object> context, String key) {
        builder.metadata(key, context.get(key));
    }
}
