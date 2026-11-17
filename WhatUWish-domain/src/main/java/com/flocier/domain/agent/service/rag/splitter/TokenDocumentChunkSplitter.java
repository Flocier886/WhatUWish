package com.flocier.domain.agent.service.rag.splitter;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 现有 Token + overlap 切片策略适配实现（迁移期默认实现）。
 */
@Component
public class TokenDocumentChunkSplitter implements DocumentChunkSplitter {

    private final TokenTextSplitter tokenTextSplitter;

    public TokenDocumentChunkSplitter(TokenTextSplitter tokenTextSplitter) {
        this.tokenTextSplitter = tokenTextSplitter;
    }

    @Override
    public List<Document> split(List<Document> documents) {
        return tokenTextSplitter.apply(documents);
    }
}
