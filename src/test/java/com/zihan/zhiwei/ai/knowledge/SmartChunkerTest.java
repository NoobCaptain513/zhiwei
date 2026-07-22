package com.zihan.zhiwei.ai.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SmartChunker 智能分块器测试")
class SmartChunkerTest {

    private SmartChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new SmartChunker();
        ReflectionTestUtils.setField(chunker, "maxTokens", 512);
        ReflectionTestUtils.setField(chunker, "overlapTokens", 64);
    }

    // ──────────────────────────────────────────
    // 基本分块
    // ──────────────────────────────────────────

    @Test
    @DisplayName("短文不分块 → 1 个 chunk")
    void shouldNotSplitShortText() {
        List<DocumentChunk> chunks = chunker.chunk("Redis 是一个开源的内存数据库", 1L, "redis.txt");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getChunkIndex()).isEqualTo(0);
        assertThat(chunks.get(0).getContent()).isEqualTo("Redis 是一个开源的内存数据库");
        assertThat(chunks.get(0).getSourceFile()).isEqualTo("redis.txt");
    }

    @Test
    @DisplayName("空文本 → 空列表")
    void shouldReturnEmptyForBlank() {
        assertThat(chunker.chunk("", 1L, "a.txt")).isEmpty();
        assertThat(chunker.chunk(null, 1L, "a.txt")).isEmpty();
    }

    // ──────────────────────────────────────────
    // 段落切分
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("段落切分")
    class ParagraphSplitTests {

        @Test
        @DisplayName("双换行 → 按段落分裂")
        void shouldSplitByParagraph() {
            String longPara = "测".repeat(300);  // ~300 tokens, 两个合并 ≈600 > 512
            String text = longPara + "\n\n" + longPara + "\n\n" + longPara;
            List<DocumentChunk> chunks = chunker.chunk(text, 1L, "test.txt");

            assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("单换行 → 不分裂")
        void shouldNotSplitSingleNewline() {
            String text = "第一行\n第二行\n第三行";
            List<DocumentChunk> chunks = chunker.chunk(text, 1L, "test.txt");

            assertThat(chunks).hasSize(1);
        }
    }

    // ──────────────────────────────────────────
    // token 估算
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("Token 估算")
    class TokenEstimationTests {

        @Test
        @DisplayName("纯中文 → 1 字 ≈ 1 token")
        void shouldEstimateChineseTokens() {
            int tokens = chunker.estimateTokens("你好世界");  // 4 chars

            assertThat(tokens).isEqualTo(4);
        }

        @Test
        @DisplayName("纯英文 → 4 字符 ≈ 1 token")
        void shouldEstimateEnglishTokens() {
            int tokens = chunker.estimateTokens("Hello");  // 5 chars

            assertThat(tokens).isEqualTo(2);  // ceil(5/4) = 2
        }

        @Test
        @DisplayName("中英混合 → 分别估算")
        void shouldEstimateMixedTokens() {
            int tokens = chunker.estimateTokens("Redis 集群");  // Redis=5en, " 集群"=2cn+1space

            assertThat(tokens).isEqualTo(4);  // 2cn + ceil(5/4)en = 2 + 2 = 4
        }

        @Test
        @DisplayName("空字符串 → 0")
        void shouldReturnZeroForEmpty() {
            assertThat(chunker.estimateTokens("")).isZero();
            assertThat(chunker.estimateTokens(null)).isZero();
        }
    }

    // ──────────────────────────────────────────
    // 超长文本滑动窗口
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("长文本滑动窗口")
    class SlidingWindowTests {

        @Test
        @DisplayName("超长段落 → 滑动窗口切分多块")
        void shouldSplitLongParagraph() {
            // 需要 > 1024 字符才会触发滑动窗口切分（charWindow = maxTokens*2 = 1024）
            String longText = "测".repeat(1200);
            List<DocumentChunk> chunks = chunker.chunk(longText, 1L, "long.txt");

            assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("chunk 带正确的偏移量")
        void shouldHaveCorrectOffsets() {
            String text = "A".repeat(200) + "\n\n" + "B".repeat(300);
            List<DocumentChunk> chunks = chunker.chunk(text, 1L, "offset.txt");

            int totalCovered = chunks.stream()
                    .mapToInt(c -> c.getEndOffset() - c.getStartOffset())
                    .sum();
            assertThat(totalCovered).isGreaterThan(0);

            for (int i = 1; i < chunks.size(); i++) {
                assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
            }
        }
    }

    // ──────────────────────────────────────────
    // 短块合并
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("短块合并")
    class MergeTests {

        @Test
        @DisplayName("多个极短段落 → 合并为一个 chunk")
        void shouldMergeShortParagraphs() {
            String text = "第一\n\n第二\n\n第三\n\n第四";
            List<DocumentChunk> chunks = chunker.chunk(text, 1L, "short.txt");

            // 极短段落应被合并
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getContent()).contains("第一");
            assertThat(chunks.get(0).getContent()).contains("第四");
        }

        @Test
        @DisplayName("合并块不超过 maxTokens")
        void shouldNotExceedMaxTokensWhenMerging() {
            // 构造刚好撑满多个短块
            String para = "A".repeat(10);
            String text = String.join("\n\n", java.util.Collections.nCopies(5, para));
            List<DocumentChunk> chunks = chunker.chunk(text, 1L, "merge.txt");

            // 所有 chunk 的 token 都不应超过 maxTokens + 一点误差
            for (DocumentChunk c : chunks) {
                assertThat(c.getTokenCount()).isLessThanOrEqualTo(chunker.getMaxTokens() + 10);
            }
        }
    }

    // ──────────────────────────────────────────
    // 配置项
    // ──────────────────────────────────────────

    @Test
    @DisplayName("配置值正确")
    void shouldHaveCorrectConfig() {
        assertThat(chunker.getMaxTokens()).isEqualTo(512);
        assertThat(chunker.getOverlapTokens()).isEqualTo(64);
    }

    // ──────────────────────────────────────────
    // DocumentChunk 构建
    // ──────────────────────────────────────────

    @Test
    @DisplayName("DocumentChunk builder 完整构建")
    void shouldBuildDocumentChunkCorrectly() {
        DocumentChunk chunk = DocumentChunk.builder()
                .documentId(42L)
                .sourceFile("redis.pdf")
                .chunkIndex(3)
                .content("Redis 集群扩容...")
                .startOffset(1024)
                .endOffset(2048)
                .tokenCount(512)
                .section("第三章")
                .build();

        assertThat(chunk.getDocumentId()).isEqualTo(42L);
        assertThat(chunk.getSourceFile()).isEqualTo("redis.pdf");
        assertThat(chunk.getChunkIndex()).isEqualTo(3);
        assertThat(chunk.getStartOffset()).isEqualTo(1024);
        assertThat(chunk.getEndOffset()).isEqualTo(2048);
        assertThat(chunk.getTokenCount()).isEqualTo(512);
        assertThat(chunk.getSection()).isEqualTo("第三章");
    }
}
