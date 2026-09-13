package com.zihan.zhiwei.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

@DisplayName("RAG 排名指标")
class RagMetricsCalculatorTest {

    private final RagMetricsCalculator calculator = new RagMetricsCalculator();

    @Test
    @DisplayName("分级多文档排序应同时计算五项指标")
    void shouldCalculateAllMetricsForGradedMultiDocumentRanking() {
        Map<String, Integer> relevance = new LinkedHashMap<>();
        relevance.put("doc-a", 3);
        relevance.put("doc-b", 2);
        relevance.put("doc-c", 1);

        RagMetricsCalculator.Metrics metrics = calculator.calculate(
                List.of("irrelevant", "doc-b", "doc-a", "other", "doc-a"), relevance, 5);

        double expectedDcg = 3.0 / log2(3) + 7.0 / log2(4);
        double idealDcg = 7.0 + 3.0 / log2(3) + 1.0 / log2(4);
        assertThat(metrics.hitRate()).isEqualTo(1.0);
        assertThat(metrics.recall()).isCloseTo(2.0 / 3.0, offset(1e-12));
        assertThat(metrics.precision()).isEqualTo(0.4);
        assertThat(metrics.mrr()).isEqualTo(0.5);
        assertThat(metrics.ndcg()).isCloseTo(expectedDcg / idealDcg, offset(1e-12));
        assertThat(metrics.firstRelevantRank()).isEqualTo(2);
        assertThat(metrics.matchedRelevantCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("重复 sourceId 不得重复提高 Precision、Recall 或 nDCG")
    void shouldIgnoreDuplicateRetrievedSourceIds() {
        RagMetricsCalculator.Metrics metrics = calculator.calculate(
                List.of("doc-a", "doc-a", "doc-b"),
                Map.of("doc-a", 3, "doc-b", 1), 3);

        double idealDcg = 7.0 + 1.0 / log2(3);
        double actualDcg = 7.0 + 1.0 / log2(4);
        assertThat(metrics.recall()).isEqualTo(1.0);
        assertThat(metrics.precision()).isCloseTo(2.0 / 3.0, offset(1e-12));
        assertThat(metrics.ndcg()).isCloseTo(actualDcg / idealDcg, offset(1e-12));
    }

    @Test
    @DisplayName("仅统计 K 以内结果且 Precision 的分母固定为 K")
    void shouldRespectCutoffAndUseKAsPrecisionDenominator() {
        RagMetricsCalculator.Metrics metrics = calculator.calculate(
                List.of("other", "doc-a"), Map.of("doc-a", 2), 1);

        assertThat(metrics.hitRate()).isZero();
        assertThat(metrics.recall()).isZero();
        assertThat(metrics.precision()).isZero();
        assertThat(metrics.mrr()).isZero();
        assertThat(metrics.ndcg()).isZero();
        assertThat(metrics.firstRelevantRank()).isEqualTo(-1);
    }

    @Test
    @DisplayName("无召回结果时五项指标均为零")
    void shouldReturnZeroMetricsForEmptyHits() {
        RagMetricsCalculator.Metrics metrics = calculator.calculate(
                List.of(), Map.of("doc-a", 3), 5);

        assertThat(metrics.hitRate()).isZero();
        assertThat(metrics.recall()).isZero();
        assertThat(metrics.precision()).isZero();
        assertThat(metrics.mrr()).isZero();
        assertThat(metrics.ndcg()).isZero();
    }

    @Test
    @DisplayName("空标注、非法等级和非法 K 应快速失败")
    void shouldRejectInvalidInputs() {
        assertThatThrownBy(() -> calculator.calculate(List.of("doc-a"), Map.of(), 5));
        assertThatThrownBy(() -> calculator.calculate(List.of("doc-a"), Map.of("doc-a", 0), 5));
        assertThatThrownBy(() -> calculator.calculate(List.of("doc-a"), Map.of("doc-a", 4), 5));
        assertThatThrownBy(() -> calculator.calculate(List.of("doc-a"), Map.of("doc-a", 3), 0));
    }

    private static void assertThatThrownBy(Runnable runnable) {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }
}
