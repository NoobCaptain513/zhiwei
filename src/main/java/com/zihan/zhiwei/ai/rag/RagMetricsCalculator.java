package com.zihan.zhiwei.ai.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure ranking-metric calculations for a single RAG query. */
public class RagMetricsCalculator {

    public Metrics calculate(List<String> retrievedSourceIds,
                             Map<String, Integer> relevanceBySource,
                             int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        validateJudgments(relevanceBySource);

        List<String> retrieved = retrievedSourceIds == null ? List.of() : retrievedSourceIds;
        Set<String> seen = new HashSet<>();
        int matched = 0;
        int firstRelevantRank = -1;
        double dcg = 0.0;

        int limit = Math.min(k, retrieved.size());
        for (int index = 0; index < limit; index++) {
            String sourceId = retrieved.get(index);
            if (sourceId == null || !seen.add(sourceId)) {
                continue;
            }
            Integer relevance = relevanceBySource.get(sourceId);
            if (relevance == null) {
                continue;
            }
            matched++;
            int rank = index + 1;
            if (firstRelevantRank < 0) {
                firstRelevantRank = rank;
            }
            dcg += gain(relevance) / log2(rank + 1.0);
        }

        double idealDcg = idealDcg(relevanceBySource.values(), k);
        double hitRate = matched > 0 ? 1.0 : 0.0;
        return new Metrics(
                hitRate,
                matched / (double) relevanceBySource.size(),
                matched / (double) k,
                firstRelevantRank > 0 ? 1.0 / firstRelevantRank : 0.0,
                idealDcg > 0.0 ? dcg / idealDcg : 0.0,
                firstRelevantRank,
                matched
        );
    }

    private static void validateJudgments(Map<String, Integer> relevanceBySource) {
        if (relevanceBySource == null || relevanceBySource.isEmpty()) {
            throw new IllegalArgumentException("at least one relevance judgment is required");
        }
        for (Map.Entry<String, Integer> entry : relevanceBySource.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("sourceId must not be blank");
            }
            Integer relevance = entry.getValue();
            if (relevance == null || relevance < 1 || relevance > 3) {
                throw new IllegalArgumentException("relevance must be between 1 and 3");
            }
        }
    }

    private static double idealDcg(Iterable<Integer> relevanceValues, int k) {
        List<Integer> sorted = new ArrayList<>();
        relevanceValues.forEach(sorted::add);
        sorted.sort(Comparator.reverseOrder());
        double result = 0.0;
        for (int index = 0; index < Math.min(k, sorted.size()); index++) {
            result += gain(sorted.get(index)) / log2(index + 2.0);
        }
        return result;
    }

    private static double gain(int relevance) {
        return Math.pow(2.0, relevance) - 1.0;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    public record Metrics(
            double hitRate,
            double recall,
            double precision,
            double mrr,
            double ndcg,
            int firstRelevantRank,
            int matchedRelevantCount
    ) {}
}
