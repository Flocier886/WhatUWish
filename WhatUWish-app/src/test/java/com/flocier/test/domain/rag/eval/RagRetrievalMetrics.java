package com.flocier.test.domain.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievalMetrics {

    private int sampleCount;

    private double hitAt1;

    private double hitAt3;

    private double hitAt5;

    private double recallAt3;

    private double recallAt5;

    private double mrr;
}
