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
public class RagRetrievalEvalCaseResult {

    private String queryId;

    private String query;

    private List<String> goldChunkIds;

    private List<String> retrievedChunkIds;

    private List<String> matchedGoldChunkIds;

    private List<String> missedGoldChunkIds;

    private int firstRelevantRank;

    private double reciprocalRank;

    private double recallAt3;

    private double recallAt5;

    private boolean hitAt1;

    private boolean hitAt3;

    private boolean hitAt5;
}
