package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.pojo.dto.RagEvaluateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("路由监控 + RAG 评估测试")
class RouterMonitorAndRagEvalTests {

    // ──────────────────────────────────────────
    // RagEvaluateService
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("RagEvaluateService 质量评估")
    class RagEvaluateTests {

        @Mock private AiRagService aiRagService;

        private RagEvaluateService evalService;

        @BeforeEach
        void setUp() {
            evalService = new RagEvaluateService(aiRagService);
        }

        @Test
        @DisplayName("全部命中 → recallRate=1.0")
        void shouldHavePerfectRecall() {
            List<RagEvaluateService.EvalItem> items = List.of(
                    new RagEvaluateService.EvalItem("Redis 扩容", "doc-redis"),
                    new RagEvaluateService.EvalItem("MySQL 索引", "doc-mysql"));

            when(aiRagService.search(eq("Redis 扩容"), eq(5), eq(20)))
                    .thenReturn(List.of(hit("doc-redis", 0.9)));
            when(aiRagService.search(eq("MySQL 索引"), eq(5), eq(20)))
                    .thenReturn(List.of(hit("doc-mysql", 0.85)));

            RagEvaluateResponse result = evalService.evaluate("test", items, 5, 20);

            assertThat(result.getRecallRate()).isCloseTo(1.0, offset(0.01));
            assertThat(result.getHitCount()).isEqualTo(2);
            assertThat(result.getTotalQueries()).isEqualTo(2);
            assertThat(result.getAvgRank()).isCloseTo(1.0, offset(0.01));
        }

        @Test
        @DisplayName("全未命中 → recallRate=0")
        void shouldHaveZeroRecall() {
            List<RagEvaluateService.EvalItem> items = List.of(
                    new RagEvaluateService.EvalItem("问题1", "src-1"));

            when(aiRagService.search(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of(hit("other-src", 0.3)));

            RagEvaluateResponse result = evalService.evaluate("test", items, 5, 20);

            assertThat(result.getRecallRate()).isCloseTo(0.0, offset(0.01));
            assertThat(result.getHitCount()).isZero();
        }

        @Test
        @DisplayName("50% 命中 → recallRate=0.5")
        void shouldHavePartialRecall() {
            List<RagEvaluateService.EvalItem> items = List.of(
                    new RagEvaluateService.EvalItem("q1", "src-1"),
                    new RagEvaluateService.EvalItem("q2", "src-2"));

            when(aiRagService.search(eq("q1"), anyInt(), anyInt()))
                    .thenReturn(List.of(hit("src-1", 0.9)));
            when(aiRagService.search(eq("q2"), anyInt(), anyInt()))
                    .thenReturn(List.of(hit("src-other", 0.3)));

            RagEvaluateResponse result = evalService.evaluate("test", items, 5, 20);

            assertThat(result.getRecallRate()).isCloseTo(0.5, offset(0.01));
            assertThat(result.getHitCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("每条 query 有 detail 记录")
        void shouldHaveDetailPerQuery() {
            List<RagEvaluateService.EvalItem> items = List.of(
                    new RagEvaluateService.EvalItem("q1", "src-1"));

            when(aiRagService.search(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of(hit("src-1", 0.95)));

            RagEvaluateResponse result = evalService.evaluate("mydataset", items, 3, 10);

            assertThat(result.getDataset()).isEqualTo("mydataset");
            assertThat(result.getDetails()).hasSize(1);
            RagEvaluateResponse.QueryDetail detail = result.getDetails().get(0);
            assertThat(detail.getQuery()).isEqualTo("q1");
            assertThat(detail.isHit()).isTrue();
            assertThat(detail.getTopRank()).isEqualTo(1);
            assertThat(detail.getFinalScore()).isCloseTo(0.95, offset(0.01));
        }

        @Test
        @DisplayName("A/B 对比 → 召回率高的胜出")
        void shouldCompareAb() {
            List<RagEvaluateService.EvalItem> items = List.of(
                    new RagEvaluateService.EvalItem("q1", "src-1"));

            when(aiRagService.search(eq("q1"), eq(3), eq(10)))
                    .thenReturn(List.of(hit("src-1", 0.9)));
            when(aiRagService.search(eq("q1"), eq(5), eq(20)))
                    .thenReturn(List.of(hit("other", 0.3)));

            RagEvaluateResponse.AbComparison comparison = evalService.compareAb(
                    items, "0.85/0.15", 3, 10, "0.7/0.3", 5, 20);

            assertThat(comparison.getVariantA()).isEqualTo("0.85/0.15");
            assertThat(comparison.getVariantB()).isEqualTo("0.7/0.3");
            assertThat(comparison.getWinner()).isEqualTo("0.85/0.15");
            assertThat(comparison.getRecallA()).isCloseTo(1.0, offset(0.01));
            assertThat(comparison.getRecallB()).isCloseTo(0.0, offset(0.01));
        }
    }

    // ──────────────────────────────────────────
    // FailoverEventLog
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("FailoverEventLog 降级事件日志")
    class FailoverEventLogTests {

        private com.zihan.zhiwei.ai.provider.health.FailoverEventLog newLog() {
            com.zihan.zhiwei.ai.provider.health.FailoverEventLog log =
                    new com.zihan.zhiwei.ai.provider.health.FailoverEventLog();
            ReflectionTestUtils.setField(log, "maxSize", 200);
            return log;
        }

        @Test
        @DisplayName("record + recent → 事件有序")
        void shouldRecordAndRetrieve() {
            var log = newLog();

            log.record(new com.zihan.zhiwei.ai.provider.failover.FailoverEvent(
                    "spring-ai-alibaba", "langchain4j-openai", "timeout", java.time.Instant.now()));
            log.record(new com.zihan.zhiwei.ai.provider.failover.FailoverEvent(
                    "langchain4j-openai", "native-dashscope", "429", java.time.Instant.now()));

            var events = log.recent(10);

            assertThat(events).hasSize(2);
            assertThat(events.get(0).fromProvider()).isEqualTo("spring-ai-alibaba");
            assertThat(events.get(1).fromProvider()).isEqualTo("langchain4j-openai");
        }

        @Test
        @DisplayName("recent(1) → 只返回最新 1 条")
        void shouldLimitRecent() {
            var log = newLog();
            log.record(evt("a", "b", "reason1"));
            log.record(evt("b", "c", "reason2"));

            assertThat(log.recent(1)).hasSize(1);
        }

        @Test
        @DisplayName("clear → 清空")
        void shouldClear() {
            var log = newLog();
            log.record(evt("a", "b", "r"));

            log.clear();

            assertThat(log.recent()).isEmpty();
        }

        @Test
        @DisplayName("超出 maxSize → 丢弃最旧")
        void shouldEvictOldest() {
            var log = newLog();
            // maxSize=200, 插入 250 条
            for (int i = 0; i < 250; i++) {
                log.record(evt("p" + i, "p" + (i + 1), "r"));
            }

            assertThat(log.recent(10)).hasSize(10);
        }
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private static RagHit hit(String sourceId, double finalScore) {
        KnowledgeChunk chunk = new KnowledgeChunk(1L, 100L, sourceId, "title", "content", 50, null);
        return new RagHit(chunk, finalScore * 0.9, finalScore * 0.1, finalScore);
    }

    private static com.zihan.zhiwei.ai.provider.failover.FailoverEvent evt(
            String from, String to, String reason) {
        return new com.zihan.zhiwei.ai.provider.failover.FailoverEvent(
                from, to, reason, java.time.Instant.now());
    }
}
