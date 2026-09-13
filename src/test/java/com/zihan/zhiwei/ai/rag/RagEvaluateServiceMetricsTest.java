package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.pojo.dto.RagEvaluateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RAG 多文档分级评测")
class RagEvaluateServiceMetricsTest {

    @Mock
    private AiRagService aiRagService;

    private RagEvaluateService service;

    @BeforeEach
    void setUp() {
        service = new RagEvaluateService(aiRagService);
    }

    @Test
    @DisplayName("多相关文档应输出宏平均 HitRate、Recall、Precision、MRR、nDCG")
    void shouldEvaluateMultipleGradedRelevantDocuments() {
        var item = new RagEvaluateService.EvalItem(
                "case-001",
                "如何配置熔断和降级链",
                List.of(
                        new RagEvaluateService.RelevantDocument("runbook-circuit", 3),
                        new RagEvaluateService.RelevantDocument("runbook-failover", 2),
                        new RagEvaluateService.RelevantDocument("architecture", 1)));
        when(aiRagService.searchRaw(anyString(), any(), anyInt(), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(List.of(
                        hit("other", 0.9),
                        hit("runbook-failover", 0.8),
                        hit("runbook-circuit", 0.7)));

        RagEvaluateResponse response = service.evaluate(
                "ops-v1", List.of(item), "keyword-heavy",
                RetrievalStrategy.KEYWORD_HEAVY, 5, 20);

        assertThat(response.getDataset()).isEqualTo("ops-v1");
        assertThat(response.getVariant()).isEqualTo("keyword-heavy");
        assertThat(response.getStrategy()).isEqualTo("KEYWORD_HEAVY");
        assertThat(response.getMetrics().getHitRate()).isEqualTo(1.0);
        assertThat(response.getMetrics().getRecall()).isCloseTo(2.0 / 3.0, offset(1e-12));
        assertThat(response.getMetrics().getPrecision()).isEqualTo(0.4);
        assertThat(response.getMetrics().getMrr()).isEqualTo(0.5);
        assertThat(response.getMetrics().getNdcg()).isBetween(0.0, 1.0);
        assertThat(response.getRecallRate()).isEqualTo(response.getMetrics().getHitRate());
        assertThat(response.getDetails().getFirst().getCaseId()).isEqualTo("case-001");
        assertThat(response.getDetails().getFirst().getRelevantDocuments()).hasSize(3);
        assertThat(response.getDetails().getFirst().getRetrievedSourceIds())
                .containsExactly("other", "runbook-failover", "runbook-circuit");
    }

    @Test
    @DisplayName("策略对比应每个查询每个策略仅检索一次并在指标相同时返回 TIE")
    void shouldCompareStrategiesOnceAndReturnTie() {
        var item = new RagEvaluateService.EvalItem("case-001", "query",
                List.of(new RagEvaluateService.RelevantDocument("doc-a", 3)));
        when(aiRagService.searchRaw(anyString(), any(), anyInt(), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(List.of(hit("doc-a", 0.9)));

        RagEvaluateResponse response = service.compareStrategies(
                "ops-v1",
                List.of(item),
                new RagEvaluateService.EvaluationVariant("hybrid", RetrievalStrategy.HYBRID, 5, 20),
                new RagEvaluateService.EvaluationVariant("vector-heavy", RetrievalStrategy.VECTOR_HEAVY, 5, 20));

        verify(aiRagService, times(2))
                .searchRaw(anyString(), any(), anyInt(), anyInt(), anyDouble(), anyDouble());
        assertThat(response.getAbComparison().getWinner()).isEqualTo("TIE");
        assertThat(response.getAbComparison().getMetricsA().getNdcg()).isEqualTo(1.0);
        assertThat(response.getAbComparison().getMetricsB().getNdcg()).isEqualTo(1.0);
    }

    private static RagHit hit(String sourceId, double score) {
        KnowledgeChunk chunk = new KnowledgeChunk(1L, 1L, sourceId, "title", "content", 10, null);
        return new RagHit(chunk, score, score, score);
    }
}
