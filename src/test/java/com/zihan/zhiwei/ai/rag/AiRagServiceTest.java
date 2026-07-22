package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.embedding.CompatibleEmbeddingClient;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AiRagService RAG 检索服务测试")
class AiRagServiceTest {

    @Mock private CompatibleEmbeddingClient embeddingClient;
    @Mock private PgVectorKnowledgeRepository repository;

    private AiRagService ragService;

    @Captor private ArgumentCaptor<float[]> embeddingCaptor;

    @BeforeEach
    void setUp() {
        ragService = new AiRagService(embeddingClient, repository);
        ReflectionTestUtils.setField(ragService, "defaultCandidateK", 20);
        ReflectionTestUtils.setField(ragService, "defaultTopK", 5);
        ReflectionTestUtils.setField(ragService, "vectorWeight", 0.85);
        ReflectionTestUtils.setField(ragService, "lexicalWeight", 0.15);
    }

    // ──────────────────────────────────────────
    // 第一部分：字面评分 lexicalScore
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("字面评分 lexicalScore")
    class LexicalScoreTests {

        @Test
        @DisplayName("完全相同文本 → 得分 1.0")
        void shouldReturnOneForIdenticalText() {
            double score = AiRagService.lexicalScore("Redis 集群扩容", "Redis 集群扩容");

            assertThat(score).isCloseTo(1.0, offset(0.01));
        }

        @Test
        @DisplayName("完全无关文本 → 得分 0.0")
        void shouldReturnZeroForUnrelatedText() {
            double score = AiRagService.lexicalScore("Redis", "MySQL");

            assertThat(score).isCloseTo(0.0, offset(0.01));
        }

        @Test
        @DisplayName("部分重叠文本 → 0 < 得分 < 1")
        void shouldReturnPartialForOverlap() {
            double score = AiRagService.lexicalScore("Redis 集群扩容", "Redis 集群监控");

            assertThat(score).isGreaterThan(0.0);
            assertThat(score).isLessThan(1.0);
        }

        @Test
        @DisplayName("空 query → 得分 0")
        void shouldReturnZeroForEmptyQuery() {
            double score = AiRagService.lexicalScore("", "some content");

            assertThat(score).isCloseTo(0.0, offset(0.01));
        }

        @Test
        @DisplayName("空 content → 得分 0")
        void shouldReturnZeroForEmptyContent() {
            double score = AiRagService.lexicalScore("query", "");

            assertThat(score).isCloseTo(0.0, offset(0.01));
        }

        @Test
        @DisplayName("两者都空 → 得分 0")
        void shouldReturnZeroForBothEmpty() {
            double score = AiRagService.lexicalScore("", "");

            assertThat(score).isCloseTo(0.0, offset(0.01));
        }

        @Test
        @DisplayName("英文大小写不敏感")
        void shouldBeCaseInsensitive() {
            double scoreIdentical = AiRagService.lexicalScore("Redis", "Redis");
            double scoreLower = AiRagService.lexicalScore("redis", "REDIS");

            assertThat(scoreIdentical).isCloseTo(scoreLower, offset(0.01));
        }

        @Test
        @DisplayName("标点符号不影响分词")
        void shouldIgnorePunctuation() {
            double scoreWithPunct = AiRagService.lexicalScore(
                    "错误码：ERROR-5001", "错误码 ERROR 5001");

            assertThat(scoreWithPunct).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("单字文本可正常计算")
        void shouldHandleSingleChar() {
            double score = AiRagService.lexicalScore("我", "我");

            assertThat(score).isCloseTo(1.0, offset(0.01));
        }
    }

    // ──────────────────────────────────────────
    // 第二部分：RAG 检索 search()
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("RAG 检索 search()")
    class SearchTests {

        @Test
        @DisplayName("正常检索 → 返回 topK 条结果，按 finalScore 降序")
        void shouldReturnTopKResultsSortedByFinalScore() {
            float[] mockVec = new float[]{0.1f, 0.2f, 0.3f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);

            List<PgVectorKnowledgeRepository.ScoredChunk> recalled = List.of(
                    scoredChunk(1L, "Redis 集群扩容指南", "详细的 Redis 集群扩容步骤...", 0.92),
                    scoredChunk(2L, "Redis 监控指标", "Redis 集群的监控指标包括...", 0.85),
                    scoredChunk(3L, "数据库备份", "每日数据库备份策略...", 0.45));
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(recalled);

            List<RagHit> hits = ragService.search("Redis 集群扩容", 2, 10);

            assertThat(hits).hasSize(2);
            assertThat(hits.get(0).chunk().title()).isEqualTo("Redis 集群扩容指南");
            assertThat(hits.get(0).finalScore()).isCloseTo(0.92 * 0.85 + lexicalPart("Redis 集群扩容", "详细的 Redis 集群扩容步骤..."), offset(0.001));
            assertThat(hits.get(1).finalScore()).isLessThan(hits.get(0).finalScore());
        }

        @Test
        @DisplayName("召回结果少 → 返回全部")
        void shouldReturnAllWhenFewerThanTopK() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);

            List<PgVectorKnowledgeRepository.ScoredChunk> recalled = List.of(
                    scoredChunk(1L, "title", "content", 0.8));
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(recalled);

            List<RagHit> hits = ragService.search("test", 5, 10);

            assertThat(hits).hasSize(1);
        }

        @Test
        @DisplayName("空召回 → 返回空列表")
        void shouldReturnEmptyWhenNothingRecalled() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(Collections.emptyList());

            List<RagHit> hits = ragService.search("nonexistent");

            assertThat(hits).isEmpty();
        }

        @Test
        @DisplayName("空 query → 抛 BusinessException")
        void shouldThrowForEmptyQuery() {
            assertThatThrownBy(() -> ragService.search(""))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("query 不能为空");

            assertThatThrownBy(() -> ragService.search(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("query 不能为空");
        }

        @Test
        @DisplayName("candidateK < topK → 自动修正 cand = topK")
        void shouldEnlargeCandWhenLessThanTop() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);

            List<PgVectorKnowledgeRepository.ScoredChunk> recalled = List.of(
                    scoredChunk(1L, "a", "b", 0.1));
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(recalled);

            ragService.search("test", 10, 3);

            verify(repository).searchByCosine(any(float[].class), eq(10));
        }

        @Test
        @DisplayName("topK 为 null → 使用默认值 5")
        void shouldUseDefaultTopKWhenNull() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(
                    java.util.stream.IntStream.range(0, 20)
                            .mapToObj(i -> scoredChunk((long) i, "t" + i, "c" + i, 0.9 - i * 0.01))
                            .toList());

            List<RagHit> hits = ragService.search("test", null, 20);

            assertThat(hits).hasSize(5);
        }

        @Test
        @DisplayName("默认参数重载 search(query)")
        void shouldUseDefaultsWithSearchQueryOnly() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);
            when(repository.searchByCosine(any(float[].class), eq(20))).thenReturn(
                    java.util.stream.IntStream.range(0, 20)
                            .mapToObj(i -> scoredChunk((long) i, "t" + i, "c" + i, 0.9 - i * 0.01))
                            .toList());

            List<RagHit> hits = ragService.search("test");

            assertThat(hits).hasSize(5);
        }
    }

    // ──────────────────────────────────────────
    // 第三部分：混合重排权重验证
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("混合重排权重")
    class HybridRerankTests {

        @Test
        @DisplayName("向量得分 + 字面得分 → finalScore = 0.85*v + 0.15*l")
        void shouldWeightVectorAndLexical() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);

            List<PgVectorKnowledgeRepository.ScoredChunk> recalled = List.of(
                    scoredChunk(1L, "title", "Redis 集群扩容", 1.0));
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(recalled);

            List<RagHit> hits = ragService.search("Redis 集群扩容", 1, 1);
            RagHit hit = hits.get(0);

            assertThat(hit.vectorScore()).isCloseTo(1.0, offset(0.001));
            assertThat(hit.lexicalScore()).isCloseTo(1.0, offset(0.001));
            assertThat(hit.finalScore()).isCloseTo(1.0, offset(0.001));
        }

        @Test
        @DisplayName("向量得分 NaN → 置零处理")
        void shouldHandleNanVectorScore() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);

            List<PgVectorKnowledgeRepository.ScoredChunk> recalled = List.of(
                    scoredChunk(1L, "title", "content", Double.NaN));
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(recalled);

            List<RagHit> hits = ragService.search("test", 1, 1);

            assertThat(hits.get(0).vectorScore()).isCloseTo(0.0, offset(0.001));
        }

        @Test
        @DisplayName("向量得分 > 1.0 → 截断到 1.0")
        void shouldClampVectorScoreAboveOne() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);

            List<PgVectorKnowledgeRepository.ScoredChunk> recalled = List.of(
                    scoredChunk(1L, "title", "content", 1.5));
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(recalled);

            List<RagHit> hits = ragService.search("test", 1, 1);

            assertThat(hits.get(0).vectorScore()).isCloseTo(1.0, offset(0.001));
        }

        @Test
        @DisplayName("embedding 传入正确的 query 文本")
        void shouldEmbedCorrectQueryText() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);
            when(repository.searchByCosine(any(float[].class), anyInt())).thenReturn(
                    List.of(scoredChunk(1L, "t", "c", 0.5)));

            ragService.search("  Redis 集群扩容  ", 1, 1);

            verify(embeddingClient).embed("Redis 集群扩容");
        }
    }

    // ──────────────────────────────────────────
    // 第四部分：upsertChunk 写入知识片段
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("写入知识片段 upsertChunk()")
    class UpsertChunkTests {

        @Test
        @DisplayName("正常写入 → embedding → 写入 pgvector")
        void shouldUpsertChunk() {
            float[] mockVec = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);
            when(repository.insert(anyLong(), anyString(), anyString(), anyString(), any(float[].class), anyInt()))
                    .thenReturn(42L);

            long id = ragService.upsertChunk(100L, "doc-1", "Redis 指南", "Redis 是一个内存数据库...");

            assertThat(id).isEqualTo(42L);
            verify(embeddingClient).embed("Redis 是一个内存数据库...");
            verify(repository).insert(eq(100L), eq("doc-1"), eq("Redis 指南"),
                    eq("Redis 是一个内存数据库..."), eq(mockVec), anyInt());
        }

        @Test
        @DisplayName("空 content → 抛异常")
        void shouldThrowForEmptyContent() {
            assertThatThrownBy(() -> ragService.upsertChunk(1L, "s", "t", ""))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("content 不能为空");

            assertThatThrownBy(() -> ragService.upsertChunk(1L, "s", "t", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("content 不能为空");
        }

        @Test
        @DisplayName("token 数 = content.length / 2，至少为 1")
        void shouldEstimateTokensCorrectly() {
            float[] mockVec = new float[]{0.1f};
            when(embeddingClient.embed(anyString())).thenReturn(mockVec);
            when(repository.insert(anyLong(), anyString(), anyString(), anyString(), any(float[].class), anyInt()))
                    .thenReturn(1L);

            ragService.upsertChunk(1L, "s", "t", "ABCDEF");  // 6 chars → 3 tokens

            verify(repository).insert(anyLong(), anyString(), anyString(), anyString(), any(float[].class), eq(3));
        }
    }

    // ──────────────────────────────────────────
    // 第五部分：pgvector 向量字面量
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("向量字面量 toVectorLiteral()")
    class VectorLiteralTests {

        @Test
        @DisplayName("浮点数组 → pgvector '[x,y,z]' 格式")
        void shouldFormatFloatArrayCorrectly() {
            String literal = PgVectorKnowledgeRepository.toVectorLiteral(new float[]{1.0f, 0.5f, 0.25f});

            assertThat(literal).isEqualTo("[1.00000000,0.50000000,0.25000000]");
        }

        @Test
        @DisplayName("单元素数组")
        void shouldHandleSingleElement() {
            String literal = PgVectorKnowledgeRepository.toVectorLiteral(new float[]{0.0f});

            assertThat(literal).isEqualTo("[0.00000000]");
        }

        @Test
        @DisplayName("空数组 → 抛异常")
        void shouldThrowForEmptyArray() {
            assertThatThrownBy(() -> PgVectorKnowledgeRepository.toVectorLiteral(new float[]{}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("embedding empty");
        }

        @Test
        @DisplayName("null → 抛异常")
        void shouldThrowForNullArray() {
            assertThatThrownBy(() -> PgVectorKnowledgeRepository.toVectorLiteral(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("1536 维向量正常格式化")
        void shouldHandle1536Dimensions() {
            float[] vec = new float[1536];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) Math.sin(i);
            }
            String literal = PgVectorKnowledgeRepository.toVectorLiteral(vec);

            assertThat(literal).startsWith("[");
            assertThat(literal).endsWith("]");
            assertThat(literal.split(",")).hasSize(1536);
        }
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private static PgVectorKnowledgeRepository.ScoredChunk scoredChunk(
            long id, String title, String content, double vectorScore) {
        KnowledgeChunk chunk = new KnowledgeChunk(id, 1L, "src-" + id, title, content, 100, null);
        return new PgVectorKnowledgeRepository.ScoredChunk(chunk, vectorScore);
    }

    private static double lexicalPart(String query, String content) {
        return 0.15 * AiRagService.lexicalScore(query, content);
    }
}
