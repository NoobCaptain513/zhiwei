package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
import com.zihan.zhiwei.pojo.dto.RagEvaluateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/**
 * RAG 检索评估：支持一条查询对应多个分级相关文档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluateService {

    private final AiRagService aiRagService;

    private final RagMetricsCalculator metricsCalculator = new RagMetricsCalculator();

    /**
     * 评估单组查询集。
     *
     * @param dataset   数据集名称
     * @param evalItems 查询列表，每项 { query, expectedSourceId }
     * @param topK      检索 topK
     * @param candidateK 候选召回数
     */
    public RagEvaluateResponse evaluate(
            String dataset,
            List<EvalItem> evalItems,
            int topK,
            int candidateK) {
        return evaluateInternal(dataset, evalItems, "legacy", "REWRITE_HYBRID", topK, candidateK,
                query -> aiRagService.search(query, topK, candidateK));
    }

    public RagEvaluateResponse evaluate(
            String dataset,
            List<EvalItem> evalItems,
            String variant,
            RetrievalStrategy strategy,
            int topK,
            int candidateK) {
        RetrievalStrategy selected = strategy == null ? RetrievalStrategy.HYBRID : strategy;
        double[] weights = weights(selected);
        return evaluateInternal(dataset, evalItems, variant, selected.name(), topK, candidateK,
                query -> aiRagService.searchRaw(query, null, topK, candidateK, weights[0], weights[1]));
    }

    /**
     * A/B 对比：同一评估集，两组参数分别检索，比较召回率和平均排名。
     */
    public RagEvaluateResponse.AbComparison compareAb(
            List<EvalItem> evalItems,
            String variantA, int topKA, int candidateKA,
            String variantB, int topKB, int candidateKB) {

        RagEvaluateResponse resultA = evaluate("A", evalItems, topKA, candidateKA);
        RagEvaluateResponse resultB = evaluate("B", evalItems, topKB, candidateKB);

        return comparison(variantA, "REWRITE_HYBRID", resultA,
                variantB, "REWRITE_HYBRID", resultB);
    }

    /** 对两个明确检索策略各执行一次，返回 A 的明细与完整对比快照。 */
    public RagEvaluateResponse compareStrategies(
            String dataset,
            List<EvalItem> evalItems,
            EvaluationVariant variantA,
            EvaluationVariant variantB) {
        RagEvaluateResponse resultA = evaluate(dataset, evalItems, variantA.name(),
                variantA.strategy(), variantA.topK(), variantA.candidateK());
        RagEvaluateResponse resultB = evaluate(dataset, evalItems, variantB.name(),
                variantB.strategy(), variantB.topK(), variantB.candidateK());
        resultA.setAbComparison(comparison(
                variantA.name(), variantA.strategy().name(), resultA,
                variantB.name(), variantB.strategy().name(), resultB));
        return resultA;
    }

    private RagEvaluateResponse evaluateInternal(
            String dataset,
            List<EvalItem> evalItems,
            String variant,
            String strategy,
            int topK,
            int candidateK,
            Function<String, List<RagHit>> retriever) {
        if (evalItems == null || evalItems.isEmpty()) {
            throw new IllegalArgumentException("evaluation set must not be empty");
        }
        if (topK <= 0 || candidateK <= 0) {
            throw new IllegalArgumentException("topK and candidateK must be positive");
        }

        long startedAt = System.currentTimeMillis();
        List<RagEvaluateResponse.QueryDetail> details = new ArrayList<>();
        int hitCount = 0;
        int rankedCount = 0;
        double totalRank = 0.0;
        double totalScore = 0.0;
        double totalHitRate = 0.0;
        double totalRecall = 0.0;
        double totalPrecision = 0.0;
        double totalMrr = 0.0;
        double totalNdcg = 0.0;

        for (EvalItem item : evalItems) {
            List<RagHit> hits = Optional.ofNullable(retriever.apply(item.query())).orElseGet(List::of);
            List<String> retrievedSourceIds = hits.stream()
                    .map(hit -> hit.chunk() == null ? null : hit.chunk().sourceId())
                    .toList();
            Map<String, Integer> relevance = item.relevantDocuments().stream()
                    .collect(LinkedHashMap::new,
                            (map, document) -> map.put(document.sourceId(), document.relevance()),
                            Map::putAll);
            RagMetricsCalculator.Metrics queryMetrics =
                    metricsCalculator.calculate(retrievedSourceIds, relevance, topK);

            String matchedSource = null;
            double matchedScore = 0.0;
            if (queryMetrics.firstRelevantRank() > 0) {
                RagHit matched = hits.get(queryMetrics.firstRelevantRank() - 1);
                matchedSource = matched.chunk().sourceId();
                matchedScore = matched.finalScore();
                hitCount++;
                rankedCount++;
                totalRank += queryMetrics.firstRelevantRank();
            }
            totalScore += hits.isEmpty() ? 0.0 : hits.getFirst().finalScore();
            totalHitRate += queryMetrics.hitRate();
            totalRecall += queryMetrics.recall();
            totalPrecision += queryMetrics.precision();
            totalMrr += queryMetrics.mrr();
            totalNdcg += queryMetrics.ndcg();

            details.add(RagEvaluateResponse.QueryDetail.builder()
                    .caseId(item.caseId())
                    .query(item.query())
                    .hit(queryMetrics.hitRate() > 0.0)
                    .topRank(queryMetrics.firstRelevantRank())
                    .finalScore(matchedScore)
                    .matchedSource(matchedSource)
                    .expectedSource(item.expectedSourceId())
                    .relevantDocuments(item.relevantDocuments().stream()
                            .map(document -> RagEvaluateResponse.RelevanceJudgment.builder()
                                    .sourceId(document.sourceId())
                                    .relevance(document.relevance())
                                    .build())
                            .toList())
                    .retrievedSourceIds(retrievedSourceIds)
                    .metrics(toResponseMetrics(topK, queryMetrics))
                    .build());
        }

        int total = evalItems.size();
        RagEvaluateResponse.Metrics metrics = RagEvaluateResponse.Metrics.builder()
                .k(topK)
                .hitRate(totalHitRate / total)
                .recall(totalRecall / total)
                .precision(totalPrecision / total)
                .mrr(totalMrr / total)
                .ndcg(totalNdcg / total)
                .build();
        return RagEvaluateResponse.builder()
                .dataset(dataset)
                .variant(variant)
                .strategy(strategy)
                .topK(topK)
                .candidateK(candidateK)
                .durationMs(System.currentTimeMillis() - startedAt)
                .totalQueries(total)
                .hitCount(hitCount)
                .recallRate(metrics.getHitRate())
                .avgRank(rankedCount > 0 ? totalRank / rankedCount : 0.0)
                .avgFinalScore(totalScore / total)
                .metrics(metrics)
                .details(details)
                .build();
    }

    private static RagEvaluateResponse.Metrics toResponseMetrics(
            int k, RagMetricsCalculator.Metrics metrics) {
        return RagEvaluateResponse.Metrics.builder()
                .k(k)
                .hitRate(metrics.hitRate())
                .recall(metrics.recall())
                .precision(metrics.precision())
                .mrr(metrics.mrr())
                .ndcg(metrics.ndcg())
                .build();
    }

    private static RagEvaluateResponse.AbComparison comparison(
            String variantA, String strategyA, RagEvaluateResponse resultA,
            String variantB, String strategyB, RagEvaluateResponse resultB) {
        String winner = winner(variantA, resultA.getMetrics(), variantB, resultB.getMetrics());
        return RagEvaluateResponse.AbComparison.builder()
                .variantA(variantA)
                .variantB(variantB)
                .strategyA(strategyA)
                .strategyB(strategyB)
                .recallA(resultA.getMetrics().getRecall())
                .recallB(resultB.getMetrics().getRecall())
                .avgRankA(resultA.getAvgRank())
                .avgRankB(resultB.getAvgRank())
                .metricsA(resultA.getMetrics())
                .metricsB(resultB.getMetrics())
                .winner(winner)
                .build();
    }

    private static String winner(String variantA, RagEvaluateResponse.Metrics metricsA,
                                 String variantB, RagEvaluateResponse.Metrics metricsB) {
        int ndcg = Double.compare(metricsA.getNdcg(), metricsB.getNdcg());
        if (ndcg != 0) return ndcg > 0 ? variantA : variantB;
        int mrr = Double.compare(metricsA.getMrr(), metricsB.getMrr());
        if (mrr != 0) return mrr > 0 ? variantA : variantB;
        int recall = Double.compare(metricsA.getRecall(), metricsB.getRecall());
        if (recall != 0) return recall > 0 ? variantA : variantB;
        return "TIE";
    }

    private static double[] weights(RetrievalStrategy strategy) {
        return switch (strategy) {
            case HYBRID -> new double[]{1.0, 0.5};
            case VECTOR_HEAVY -> new double[]{1.5, 0.3};
            case KEYWORD_HEAVY -> new double[]{0.5, 1.5};
        };
    }

    public record EvaluationVariant(
            String name, RetrievalStrategy strategy, int topK, int candidateK) {
        public EvaluationVariant {
            strategy = strategy == null ? RetrievalStrategy.HYBRID : strategy;
            name = name == null || name.isBlank() ? strategy.name() : name;
            if (topK <= 0 || candidateK <= 0) {
                throw new IllegalArgumentException("topK and candidateK must be positive");
            }
        }
    }

    public record RelevantDocument(String sourceId, int relevance) {
        public RelevantDocument {
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException("sourceId must not be blank");
            }
            if (relevance < 1 || relevance > 3) {
                throw new IllegalArgumentException("relevance must be between 1 and 3");
            }
        }
    }

    /** 评估集条目；双参数构造器兼容旧的单相关文档调用。 */
    public record EvalItem(String caseId, String query, List<RelevantDocument> relevantDocuments) {
        public EvalItem {
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("query must not be blank");
            }
            if (relevantDocuments == null || relevantDocuments.isEmpty()) {
                throw new IllegalArgumentException("at least one relevant document is required");
            }
            LinkedHashMap<String, RelevantDocument> unique = new LinkedHashMap<>();
            for (RelevantDocument document : relevantDocuments) {
                if (document == null || unique.putIfAbsent(document.sourceId(), document) != null) {
                    throw new IllegalArgumentException("relevance judgments must be non-null and unique");
                }
            }
            relevantDocuments = List.copyOf(unique.values());
        }

        public EvalItem(String query, String expectedSourceId) {
            this(null, query, List.of(new RelevantDocument(expectedSourceId, 3)));
        }

        public String expectedSourceId() {
            return relevantDocuments.getFirst().sourceId();
        }
    }
}