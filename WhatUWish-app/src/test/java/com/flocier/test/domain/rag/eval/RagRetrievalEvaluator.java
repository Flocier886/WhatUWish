package com.flocier.test.domain.rag.eval;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RagRetrievalEvaluator {

    private RagRetrievalEvaluator() {
    }

    public static RagRetrievalEvalCaseResult evaluateCase(RagRetrievalEvalSample sample, List<String> retrievedChunkIds) {
        List<String> normalizedRetrieved = retrievedChunkIds == null ? List.of() : retrievedChunkIds;
        Set<String> gold = new LinkedHashSet<>(sample.getGoldChunkIds() == null ? List.of() : sample.getGoldChunkIds());
        List<String> matchedGoldChunkIds = new ArrayList<>();

        int firstRelevantRank = 0;
        int hitCountAt3 = 0;
        int hitCountAt5 = 0;

        for (int i = 0; i < normalizedRetrieved.size(); i++) {
            String candidate = normalizedRetrieved.get(i);
            if (!gold.contains(candidate)) {
                continue;
            }
            int rank = i + 1;
            if (!matchedGoldChunkIds.contains(candidate)) {
                matchedGoldChunkIds.add(candidate);
            }
            if (firstRelevantRank == 0) {
                firstRelevantRank = rank;
            }
            if (rank <= 3) {
                hitCountAt3++;
            }
            if (rank <= 5) {
                hitCountAt5++;
            }
        }

        double reciprocalRank = firstRelevantRank == 0 ? 0D : 1D / firstRelevantRank;
        int goldSize = CollectionUtils.isEmpty(gold) ? 1 : gold.size();
        List<String> missedGoldChunkIds = gold.stream()
                .filter(goldChunkId -> !matchedGoldChunkIds.contains(goldChunkId))
                .toList();

        return RagRetrievalEvalCaseResult.builder()
                .queryId(sample.getQueryId())
                .query(sample.getQuery())
                .goldChunkIds(new ArrayList<>(gold))
                .retrievedChunkIds(new ArrayList<>(normalizedRetrieved))
                .matchedGoldChunkIds(matchedGoldChunkIds)
                .missedGoldChunkIds(missedGoldChunkIds)
                .firstRelevantRank(firstRelevantRank)
                .reciprocalRank(reciprocalRank)
                .recallAt3((double) hitCountAt3 / goldSize)
                .recallAt5((double) hitCountAt5 / goldSize)
                .hitAt1(firstRelevantRank == 1)
                .hitAt3(firstRelevantRank > 0 && firstRelevantRank <= 3)
                .hitAt5(firstRelevantRank > 0 && firstRelevantRank <= 5)
                .build();
    }

    public static RagRetrievalMetrics summarize(List<RagRetrievalEvalCaseResult> caseResults) {
        if (CollectionUtils.isEmpty(caseResults)) {
            return RagRetrievalMetrics.builder().sampleCount(0).build();
        }

        int size = caseResults.size();
        double hitAt1 = 0D;
        double hitAt3 = 0D;
        double hitAt5 = 0D;
        double recallAt3 = 0D;
        double recallAt5 = 0D;
        double mrr = 0D;

        for (RagRetrievalEvalCaseResult caseResult : caseResults) {
            hitAt1 += caseResult.isHitAt1() ? 1D : 0D;
            hitAt3 += caseResult.isHitAt3() ? 1D : 0D;
            hitAt5 += caseResult.isHitAt5() ? 1D : 0D;
            recallAt3 += caseResult.getRecallAt3();
            recallAt5 += caseResult.getRecallAt5();
            mrr += caseResult.getReciprocalRank();
        }

        return RagRetrievalMetrics.builder()
                .sampleCount(size)
                .hitAt1(hitAt1 / size)
                .hitAt3(hitAt3 / size)
                .hitAt5(hitAt5 / size)
                .recallAt3(recallAt3 / size)
                .recallAt5(recallAt5 / size)
                .mrr(mrr / size)
                .build();
    }

    public static String toMarkdown(String scenarioName,
                                    RagRetrievalMetrics metrics,
                                    List<RagRetrievalEvalCaseResult> caseResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(scenarioName).append("\n\n");
        sb.append("| metric | value |\n");
        sb.append("| --- | ---: |\n");
        sb.append("| sampleCount | ").append(metrics.getSampleCount()).append(" |\n");
        sb.append("| Hit@1 | ").append(percent(metrics.getHitAt1())).append(" |\n");
        sb.append("| Hit@3 | ").append(percent(metrics.getHitAt3())).append(" |\n");
        sb.append("| Hit@5 | ").append(percent(metrics.getHitAt5())).append(" |\n");
        sb.append("| Recall@3 | ").append(percent(metrics.getRecallAt3())).append(" |\n");
        sb.append("| Recall@5 | ").append(percent(metrics.getRecallAt5())).append(" |\n");
        sb.append("| MRR | ").append(decimal(metrics.getMrr())).append(" |\n\n");
        sb.append("| queryId | firstRelevantRank | RR | Hit@3 | Recall@3 | matchedGold | missedGold | retrievedTop5 |\n");
        sb.append("| --- | ---: | ---: | ---: | ---: | --- | --- | --- |\n");
        for (RagRetrievalEvalCaseResult caseResult : caseResults) {
            sb.append("| ")
                    .append(caseResult.getQueryId())
                    .append(" | ")
                    .append(caseResult.getFirstRelevantRank())
                    .append(" | ")
                    .append(decimal(caseResult.getReciprocalRank()))
                    .append(" | ")
                    .append(caseResult.isHitAt3())
                    .append(" | ")
                    .append(percent(caseResult.getRecallAt3()))
                    .append(" | ")
                    .append(caseResult.getMatchedGoldChunkIds())
                    .append(" | ")
                    .append(caseResult.getMissedGoldChunkIds())
                    .append(" | ")
                    .append(caseResult.getRetrievedChunkIds().stream().limit(5).toList())
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100D);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
