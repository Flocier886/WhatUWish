package com.flocier.config;

import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AiAgentConfig {

    /**
     * RAG 向量库与主链路聊天模型解耦：
     * embedding 默认沿用当前 chat 的来源，但可以在 yml 中单独切换到另一套 provider。
     */
    @Bean("vectorStore")
    public PgVectorStore pgVectorStore(@Value("${reactor.embedding.base-url:${spring.ai.openai.base-url}}") String embeddingBaseUrl,
                                       @Value("${reactor.embedding.api-key:${spring.ai.openai.api-key}}") String embeddingApiKey,
                                       @Value("${reactor.embedding.vector-table-name:${spring.ai.vectorstore.pgvector.table-name:vector_store_test}}") String vectorTableName,
                                       @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .build();

        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(openAiApi);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(vectorTableName)
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(2000,50,50,0,true);
    }
}
