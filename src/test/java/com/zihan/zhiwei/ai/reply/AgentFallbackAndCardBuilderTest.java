package com.zihan.zhiwei.ai.reply;

import com.zihan.zhiwei.ai.intent.AgentIntent;
import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.zihan.zhiwei.ai.tool.ToolResultCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 兜底 + RagCardBuilder 测试")
class AgentFallbackAndCardBuilderTest {

    @Mock private AiRagService aiRagService;

    private RagCardBuilder ragCardBuilder;
    private ResultCardAssembler resultCardAssembler;
    private ToolResultCollector toolResultCollector;
    private AgentFallbackHandler fallbackHandler;

    @BeforeEach
    void setUp() {
        ragCardBuilder = new RagCardBuilder();
        resultCardAssembler = new ResultCardAssembler(new com.fasterxml.jackson.databind.ObjectMapper());
        toolResultCollector = new ToolResultCollector();
        fallbackHandler = new AgentFallbackHandler(
                aiRagService, ragCardBuilder, resultCardAssembler, toolResultCollector);
    }

    // ──────────────────────────────────────────
    // AgentFallbackHandler
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("AgentFallbackHandler 兜底逻辑")
    class FallbackHandlerTests {

        @Test
        @DisplayName("有工具结果 → 不兜底，返回 null")
        void shouldSkipWhenToolsFilled() {
            toolResultCollector.add(ToolCallResult.builder()
                    .toolName("queryServerStatus").success(true).data("{}").build());

            AgentReply result = fallbackHandler.fallbackIfNeeded(
                    "test", "short", AgentIntent.FAULT);

            assertThat(result).isNull();
            verifyNoInteractions(aiRagService);
        }

        @Test
        @DisplayName("模型回复很长 → 不兜底")
        void shouldSkipWhenReplyIsRich() {
            String longReply = "服务器目前运行正常，CPU 使用率 23%，内存 61%，磁盘空间充足。建议关注 OOM 配置参数。";

            AgentReply result = fallbackHandler.fallbackIfNeeded(
                    "redis 宕机", longReply, AgentIntent.FAULT);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("模型回复短且无结构化 → 触发兜底，RAG 检索 + 卡片")
        void shouldTriggerFallbackForShortReply() {
            toolResultCollector.clear();
            List<RagHit> ragHits = List.of(new RagHit(
                    new KnowledgeChunk(1L, 100L, "doc-1", "Redis 扩容指南", "扩容步骤...", 50, null),
                    0.9, 0.05, 0.78));
            when(aiRagService.search(eq("redis 问题"), anyInt(), anyInt())).thenReturn(ragHits);

            AgentReply result = fallbackHandler.fallbackIfNeeded(
                    "redis 问题", "不太清楚", AgentIntent.RAG);

            assertThat(result).isNotNull();
            assertThat(result.getText()).contains("知识库");
            assertThat(result.getText()).contains("1 条相关结果");
            assertThat(result.getCards()).hasSize(1);
            assertThat(result.getCards().get(0).getType()).isEqualTo("rag");
            assertThat(result.getCards().get(0).getTitle()).isEqualTo("Redis 扩容指南");
        }

        @Test
        @DisplayName("RAG 无结果 → 兜底回复无额外卡片")
        void shouldFallbackWithoutCardsWhenRagEmpty() {
            toolResultCollector.clear();
            when(aiRagService.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            AgentReply result = fallbackHandler.fallbackIfNeeded(
                    "test", "不清楚", AgentIntent.RAG);

            assertThat(result).isNotNull();
            assertThat(result.getCards()).isEmpty();
        }
    }

    // ──────────────────────────────────────────
    // RagCardBuilder
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("RagCardBuilder 卡片构建")
    class RagCardBuilderTests {

        @Test
        @DisplayName("RAG hits → rag 类型 Card")
        void shouldBuildRagCards() {
            List<RagHit> hits = List.of(
                    new RagHit(new KnowledgeChunk(1L, 100L, "doc-redis", "Redis 指南", "内容A", 50, null),
                            0.92, 0.08, 0.85),
                    new RagHit(new KnowledgeChunk(2L, 200L, "doc-mysql", "MySQL 指南", "内容B", 30, null),
                            0.80, 0.10, 0.73));

            List<AgentReply.Card> cards = ragCardBuilder.buildCards(hits);

            assertThat(cards).hasSize(2);
            assertThat(cards.get(0).getType()).isEqualTo("rag");
            assertThat(cards.get(0).getTitle()).isEqualTo("Redis 指南");
            assertThat(cards.get(0).getSourceId()).isEqualTo("doc-redis");
            assertThat(cards.get(0).getFields()).containsEntry("内容", "内容A");
            assertThat(cards.get(0).getFields()).containsEntry("向量分", "0.9200");

            assertThat(cards.get(1).getTitle()).isEqualTo("MySQL 指南");
        }

        @Test
        @DisplayName("空 hits → 空列表")
        void shouldReturnEmptyForNoHits() {
            assertThat(ragCardBuilder.buildCards(List.of())).isEmpty();
            assertThat(ragCardBuilder.buildCards(null)).isEmpty();
        }

        @Test
        @DisplayName("title 为 null → 兜底 '知识片段'")
        void shouldUseDefaultTitleForNull() {
            RagHit hit = new RagHit(new KnowledgeChunk(1L, 100L, "src", null, "content", 10, null),
                    0.5, 0.1, 0.4);

            List<AgentReply.Card> cards = ragCardBuilder.buildCards(List.of(hit));

            assertThat(cards.get(0).getTitle()).isEqualTo("知识片段");
        }

        @Test
        @DisplayName("sourceId 为 null → 使用 rag:id 兜底")
        void shouldUseDefaultSourceForNull() {
            RagHit hit = new RagHit(new KnowledgeChunk(42L, 100L, null, "title", "content", 10, null),
                    0.5, 0.1, 0.4);

            List<AgentReply.Card> cards = ragCardBuilder.buildCards(List.of(hit));

            assertThat(cards.get(0).getSourceId()).isEqualTo("rag:42");
        }

        @Test
        @DisplayName("含 documentId → fields 含文档ID")
        void shouldIncludeDocumentId() {
            RagHit hit = new RagHit(new KnowledgeChunk(1L, 100L, "src", "title", "content", 10, null),
                    0.5, 0.1, 0.4);

            List<AgentReply.Card> cards = ragCardBuilder.buildCards(List.of(hit));

            assertThat(cards.get(0).getFields()).containsEntry("文档ID", "100");
        }
    }
}
