package com.flocier.test.domain.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievalEvalSample {

    private String queryId;

    private String query;

    private List<String> goldChunkIds;

    private String knowledgeTag;

    private String category;
}
