package com.flocier.domain.agent.model.vo;

import com.flocier.domain.agent.service.armory.support.RagAdvanceAdvisorBeansProvider;
import com.flocier.domain.agent.service.armory.node.factory.element.RagAnswerAdvanceAdvisor;
import com.flocier.domain.agent.service.rag.retrieval.EnhancedRagRetriever;
import com.flocier.domain.agent.service.armory.node.factory.element.RagAnswerAdvisor;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;


@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AiClientAdvisorTypeEnumVO {
    CHAT_MEMORY("ChatMemory","上下文记忆(内存模式)") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     RagAdvanceAdvisorBeansProvider ragAdvanceBeans) {
            AiClientAdvisorVO.ChatMemory chatMemory=aiClientAdvisorVO.getChatMemory();
            //这里设置的MessageWindowChatMemory是指会话的存储方法，这里并没有给PromptChatMemoryAdvisor设置conversionId，也就是说所以会话都只会存在一个List中而不是按桶进行Map存储，如果调用client时指明用哪个维度查询记忆也会无效
            return PromptChatMemoryAdvisor.builder(
                            MessageWindowChatMemory.builder()
                                    .maxMessages(chatMemory.getMaxMessages())
                                    .build()
                    )
                    //.conversationId(ChatMemory.CONVERSATION_ID) //ChatMemory.CONVERSATION_ID就是本来的默认配置，这里可以不用配置
                    .build();
        }
    },
    RAG_ANSWER("RagAnswer","知识库") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     RagAdvanceAdvisorBeansProvider ragAdvanceBeans) {
            AiClientAdvisorVO.RagAnswer ragAnswer=aiClientAdvisorVO.getRagAnswer();
            return new RagAnswerAdvisor(vectorStore, SearchRequest.builder()
                    .topK(ragAnswer.getTopK())
                    .filterExpression(ragAnswer.getFilterExpression())
                    .build());
        }
    },
    RAG_ANSWER_ADVANCE("RagAnswerAdvance","知识库(改写+可选BM25+可选精排)") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     RagAdvanceAdvisorBeansProvider ragAdvanceBeans) {
            AiClientAdvisorVO.RagAnswerAdvance cfg = aiClientAdvisorVO.getRagAnswerAdvance();
            if (cfg == null) {
                throw new RuntimeException("err! RagAnswerAdvance advisor requires ragAnswerAdvance config!");
            }
            SearchRequest vectorRequest = SearchRequest.builder()
                    .topK(cfg.getTopK())
                    .filterExpression(cfg.getFilterExpression())
                    .build();

            EnhancedRagRetriever.QueryRewriter queryRewriter = null;
            if (StringUtils.hasText(cfg.getQueryRewriterType())) {
                queryRewriter = ragAdvanceBeans.getQueryRewriter(cfg.getQueryRewriterType().trim());
            }

            EnhancedRagRetriever.Reranker reranker = null;
            if (StringUtils.hasText(cfg.getRerankerType())) {
                reranker = ragAdvanceBeans.getReranker(cfg.getRerankerType().trim());
            }

            EnhancedRagRetriever.KeywordRetriever keywordRetriever = null;
            if (StringUtils.hasText(cfg.getKeywordRetrieverType())) {
                keywordRetriever = ragAdvanceBeans.getKeywordRetriever(cfg.getKeywordRetrieverType().trim());
            }

            return new RagAnswerAdvanceAdvisor(
                    vectorStore,
                    vectorRequest,
                    keywordRetriever,
                    cfg.getKeywordTopK(),
                    queryRewriter,
                    reranker,
                    cfg.getRerankTopK()
            );
        }
    },

    ;

    private String code;
    private String info;

    private static final Map<String,AiClientAdvisorTypeEnumVO> CODE_MAP=new HashMap<>();
    static {
        for (AiClientAdvisorTypeEnumVO enumVO:values()){
            CODE_MAP.put(enumVO.getCode(),enumVO);
        }
    }

    public static AiClientAdvisorTypeEnumVO getByCode(String code){
        AiClientAdvisorTypeEnumVO enumVO = CODE_MAP.get(code);
        if(enumVO==null){
            throw new RuntimeException("err! advisorType " + code + " not exist!");
        }
        return enumVO;
    }
    /**
     * 直接在这里配置各种类型Advisor的实例化对象
     * */
    public abstract Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                          RagAdvanceAdvisorBeansProvider ragAdvanceBeans);
}
