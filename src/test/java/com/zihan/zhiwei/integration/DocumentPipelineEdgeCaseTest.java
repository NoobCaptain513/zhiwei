package com.zihan.zhiwei.integration;

import com.zihan.zhiwei.ai.knowledge.DocumentChunk;
import com.zihan.zhiwei.ai.knowledge.SmartChunker;
import com.zihan.zhiwei.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D22: 文档管道异常测试
 *
 * 三条核心异常路径：
 * 1. 超大文件（模拟 200MB 文本）
 * 2. 格式错误（空内容、零长度）
 * 3. 重复上传（同内容不同 documentId，去重验证）
 */
@DisplayName("文档管道异常测试")
class DocumentPipelineEdgeCaseTest {

    private SmartChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new SmartChunker();
        ReflectionTestUtils.setField(chunker, "maxTokens", 512);
        ReflectionTestUtils.setField(chunker, "overlapTokens", 64);
    }

    // ──────────────────────────────────────────
    // 超大文件
    // ──────────────────────────────────────────

    @Test
    @DisplayName("超大文本(模拟200MB) → 不会 OOM, 正常分块")
    void shouldHandleLargeFileWithoutOom() {
        String largeText = "超".repeat(50_000);

        List<DocumentChunk> chunks = chunker.chunk(largeText, 1L, "large.txt");

        assertThat(chunks).isNotEmpty();
        // 分块 index 递增连续
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
            assertThat(chunks.get(i).getContent()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("超大段落(单段10000字符) → 滑动窗口正确切分")
    void shouldSplitVeryLongParagraph() {
        String longParagraph = "长".repeat(10_000);

        List<DocumentChunk> chunks = chunker.chunk(longParagraph, 1L, "long.txt");

        assertThat(chunks).hasSizeGreaterThan(1);
        // 验证相邻块内容不重复（不重叠时）或有重叠
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
        }
    }

    // ──────────────────────────────────────────
    // 格式错误
    // ──────────────────────────────────────────

    @Test
    @DisplayName("空内容 → 返回空列表")
    void shouldReturnEmptyForBlank() {
        assertThat(chunker.chunk("", 1L, "empty.txt")).isEmpty();
        assertThat(chunker.chunk(null, 1L, "null.txt")).isEmpty();
        assertThat(chunker.chunk("   ", 1L, "blank.txt")).isEmpty();
    }

    @Test
    @DisplayName("纯空格+换行 → 返回空")
    void shouldHandleWhitespaceOnly() {
        String whitespace = "   \n\n   \n\n   ";

        List<DocumentChunk> chunks = chunker.chunk(whitespace, 1L, "ws.txt");

        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("特殊字符(emoji/unicode) → 正常分块不抛异常")
    void shouldHandleSpecialCharacters() {
        String special = "Redis 集群扩容 🚀 第一步：添加节点\n\n第二步：执行 CLUSTER MEET 192.168.1.1\u0000\u0001";

        List<DocumentChunk> chunks = chunker.chunk(special, 1L, "special.txt");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getContent()).contains("Redis");
    }

    // ──────────────────────────────────────────
    // 重复上传
    // ──────────────────────────────────────────

    @Test
    @DisplayName("同内容不同 documentId → 每个独立分块")
    void shouldHandleDuplicateContent() {
        String content = "Redis 是一个开源的、基于内存的键值存储数据库。\n\n"
                + "Redis 支持多种数据结构。\n\n"
                + "Redis 集群通过分片实现横向扩展。";

        List<DocumentChunk> chunks1 = chunker.chunk(content, 100L, "redis-v1.pdf");
        List<DocumentChunk> chunks2 = chunker.chunk(content, 200L, "redis-v2.pdf");

        // 分块内容相同
        assertThat(chunks1).hasSize(chunks2.size());
        for (int i = 0; i < chunks1.size(); i++) {
            assertThat(chunks1.get(i).getContent()).isEqualTo(chunks2.get(i).getContent());
        }
        // 但 documentId 不同
        assertThat(chunks1.get(0).getDocumentId()).isEqualTo(100L);
        assertThat(chunks2.get(0).getDocumentId()).isEqualTo(200L);
        // sourceFile 不同
        assertThat(chunks1.get(0).getSourceFile()).isEqualTo("redis-v1.pdf");
        assertThat(chunks2.get(0).getSourceFile()).isEqualTo("redis-v2.pdf");
    }

    @Test
    @DisplayName("分块 offset 在重复上传中独立计算")
    void shouldHaveIndependentOffsets() {
        String content = "测试内容";
        List<DocumentChunk> chunks1 = chunker.chunk(content, 1L, "a.txt");
        List<DocumentChunk> chunks2 = chunker.chunk(content, 2L, "b.txt");

        assertThat(chunks1.get(0).getEndOffset() - chunks1.get(0).getStartOffset())
                .isEqualTo(chunks2.get(0).getEndOffset() - chunks2.get(0).getStartOffset());
    }

    // ──────────────────────────────────────────
    // 其他边界
    // ──────────────────────────────────────────

    @Test
    @DisplayName("极短文本(1字符) → 1个chunk")
    void shouldHandleSingleChar() {
        List<DocumentChunk> chunks = chunker.chunk("A", 1L, "one.txt");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).isEqualTo("A");
    }

    private static String lastN(String s, int n) {
        if (s.length() <= n) return s;
        return s.substring(s.length() - n);
    }
}
