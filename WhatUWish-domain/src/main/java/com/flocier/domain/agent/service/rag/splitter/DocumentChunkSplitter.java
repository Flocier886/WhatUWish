package com.flocier.domain.agent.service.rag.splitter;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 文档切片抽象接口。
 * 通过接口解耦具体切片策略（Token、语义边界、父子切片等）。
 */
public interface DocumentChunkSplitter {

    List<Document> split(List<Document> documents);
}
