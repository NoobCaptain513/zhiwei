package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RagMessageAugmentor 统一 RAG 注入测试")
class RagMessageAugmentorTest {

    @Mock private RagContextBuilder ragContextBuilder;

    private RagMessageAugmentor augmentor;

    private static final String USER_MSG = "Redis 集群如何扩容？";
    private static final String RAG_BLOCK = """
            你是企业运维助手。请优先依据下列知识库片段回答。

            【知识库检索结果】
            - [doc-1] Redis 集群扩容指南
                Redis 集群扩容需要先添加节点，再执行 rebalance...
                (score=0.9234)
            """;

    @BeforeEach
    void setUp() {
        augmentor = new RagMessageAugmentor(ragContextBuilder);
        ReflectionTestUtils.setField(augmentor, "injectOnChat", true);
    }

    // ──────────────────────────────────────────
    // 开关控制
    // ──────────────────────────────────────────

    @Test
    @DisplayName("injectOnChat=false → 原样返回，不调 RAG")
    void shouldBypassWhenDisabled() {
        ReflectionTestUtils.setField(augmentor, "injectOnChat", false);
        List<ProviderChatMessage> messages = List.of(
                new ProviderChatMessage("user", USER_MSG));

        List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

        assertThat(result).isSameAs(messages);
        verifyNoInteractions(ragContextBuilder);
    }

    // ──────────────────────────────────────────
    // 边界：空/无效输入
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("输入校验")
    class InputValidationTests {

        @Test
        @DisplayName("messages 为 null → 返回 null")
        void shouldReturnNullForNullMessages() {
            assertThat(augmentor.augmentIfEnabled(null)).isNull();
            verifyNoInteractions(ragContextBuilder);
        }

        @Test
        @DisplayName("messages 为空列表 → 返回空列表")
        void shouldReturnEmptyForEmptyMessages() {
            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(List.of());

            assertThat(result).isEmpty();
            verifyNoInteractions(ragContextBuilder);
        }

        @Test
        @DisplayName("messages 无 user 角色 → 返回原消息")
        void shouldReturnUnchangedWhenNoUserRole() {
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("system", "你是助手"),
                    new ProviderChatMessage("assistant", "你好，有什么可以帮助你的？"));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).isSameAs(messages);
            verifyNoInteractions(ragContextBuilder);
        }

        @Test
        @DisplayName("最后一条 user 消息为空 → 返回原消息")
        void shouldReturnUnchangedWhenLastUserBlank() {
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("user", ""));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).isSameAs(messages);
            verifyNoInteractions(ragContextBuilder);
        }

        @Test
        @DisplayName("RAG 检索无结果 → 返回原消息")
        void shouldReturnUnchangedWhenRagEmpty() {
            when(ragContextBuilder.buildContextBlock(USER_MSG)).thenReturn("");
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("user", USER_MSG));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).isSameAs(messages);
        }

        @Test
        @DisplayName("RAG 返回 null → 返回原消息")
        void shouldReturnUnchangedWhenRagNull() {
            when(ragContextBuilder.buildContextBlock(USER_MSG)).thenReturn(null);
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("user", USER_MSG));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).isSameAs(messages);
        }
    }

    // ──────────────────────────────────────────
    // 核心逻辑：注入 system 消息
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("注入行为")
    class InjectionTests {

        @Test
        @DisplayName("单条 user 消息 → 前置 system + 原消息")
        void shouldPrependSystemMessage() {
            when(ragContextBuilder.buildContextBlock(USER_MSG)).thenReturn(RAG_BLOCK);
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("user", USER_MSG));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).role()).isEqualTo("system");
            assertThat(result.get(0).content()).isEqualTo(RAG_BLOCK);
            assertThat(result.get(1).role()).isEqualTo("user");
            assertThat(result.get(1).content()).isEqualTo(USER_MSG);
        }

        @Test
        @DisplayName("多轮对话 → 用最后一条 user 消息检索，system 插入最前")
        void shouldUseLastUserMessageForRag() {
            when(ragContextBuilder.buildContextBlock("第三个问题")).thenReturn(RAG_BLOCK);
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("system", "你是工程师"),
                    new ProviderChatMessage("user", "第一个问题"),
                    new ProviderChatMessage("assistant", "第一个回答"),
                    new ProviderChatMessage("user", "第二个问题"),
                    new ProviderChatMessage("assistant", "第二个回答"),
                    new ProviderChatMessage("user", "第三个问题"));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).hasSize(7);
            assertThat(result.get(0).role()).isEqualTo("system");
            assertThat(result.get(0).content()).isEqualTo(RAG_BLOCK);
            assertThat(result.get(1).role()).isEqualTo("system");
            assertThat(result.get(1).content()).isEqualTo("你是工程师");

            verify(ragContextBuilder).buildContextBlock("第三个问题");
        }

        @Test
        @DisplayName("大小写不敏感匹配 user 角色")
        void shouldMatchUserCaseInsensitively() {
            when(ragContextBuilder.buildContextBlock("Hello")).thenReturn(RAG_BLOCK);
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("User", "Hello"));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).role()).isEqualTo("system");
        }

        @Test
        @DisplayName("原消息列表不被修改 → 返回新列表")
        void shouldNotMutateOriginalMessages() {
            when(ragContextBuilder.buildContextBlock(USER_MSG)).thenReturn(RAG_BLOCK);
            List<ProviderChatMessage> messages = new ArrayList<>(List.of(
                    new ProviderChatMessage("user", USER_MSG)));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).isNotSameAs(messages);
            assertThat(messages).hasSize(1);
        }

        @Test
        @DisplayName("检索 query 取最后一条 user 消息的 content，trim 处理")
        void shouldTrimUserMessageForQuery() {
            when(ragContextBuilder.buildContextBlock("Redis 扩容")).thenReturn(RAG_BLOCK);
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("user", "  Redis 扩容  "));

            augmentor.augmentIfEnabled(messages);

            verify(ragContextBuilder).buildContextBlock("Redis 扩容");
        }

        @Test
        @DisplayName("user 在前 assist 在后的单轮对话")
        void shouldHandleSingleTurn() {
            when(ragContextBuilder.buildContextBlock("帮我查日志")).thenReturn(RAG_BLOCK);
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("system", "初始 prompt"),
                    new ProviderChatMessage("user", "帮我查日志"));

            List<ProviderChatMessage> result = augmentor.augmentIfEnabled(messages);

            assertThat(result).hasSize(3);
            assertThat(result.get(0).role()).isEqualTo("system");
            assertThat(result.get(0).content()).isEqualTo(RAG_BLOCK);
            assertThat(result.get(1).role()).isEqualTo("system");
            assertThat(result.get(2).role()).isEqualTo("user");
        }
    }

    // ──────────────────────────────────────────
    // 端到端：模拟 ChatServiceImpl 调用链
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("端到端：ChatServiceImpl 集成")
    class ChatServiceIntegrationTests {

        @Test
        @DisplayName("增强结果 → 发给任意 Provider，所有 Provider 看到相同 context")
        void shouldProvideConsistentContextToAllProviders() {
            when(ragContextBuilder.buildContextBlock(USER_MSG)).thenReturn(RAG_BLOCK);
            List<ProviderChatMessage> messages = List.of(
                    new ProviderChatMessage("user", USER_MSG));

            // 模拟 ChatService.augmentIfEnabled → 发给三个 Provider
            List<ProviderChatMessage> augmented = augmentor.augmentIfEnabled(messages);

            assertThat(augmented.get(0).content()).isEqualTo(RAG_BLOCK);
            assertThat(augmented.get(0).content()).contains("Redis 集群扩容指南");
            assertThat(augmented.get(0).content()).contains("(score=0.9234)");
        }
    }

    // ──────────────────────────────────────────
    // 第五部分：RagContextBuilder 模板渲染
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("RagContextBuilder 模板")
    class ContextBuilderTests {

        @Test
        @DisplayName("空 hits → 返回空字符串")
        void shouldReturnEmptyForNoHits() {
            RagContextBuilder builder = new RagContextBuilder(null);
            String block = builder.buildContextBlock(List.of());

            assertThat(block).isEmpty();
        }

        @Test
        @DisplayName("null hits → 返回空字符串")
        void shouldReturnEmptyForNullHits() {
            RagContextBuilder builder = new RagContextBuilder(null);
            String block = builder.buildContextBlock((List<RagHit>) null);

            assertThat(block).isEmpty();
        }

        @Test
        @DisplayName("单条命中 → 输出含标题/来源/content/score")
        void shouldRenderSingleHit() {
            RagContextBuilder builder = new RagContextBuilder(null);
            KnowledgeChunk chunk = new KnowledgeChunk(1L, 100L, "doc-redis",
                    "Redis 扩容指南", "扩容步骤：1.添加节点 2.执行rebalance", 50, null);
            RagHit hit = new RagHit(chunk, 0.92, 0.08, 0.85);

            String block = builder.buildContextBlock(List.of(hit));

            assertThat(block).contains("你是企业运维助手");
            assertThat(block).contains("【知识库检索结果】");
            assertThat(block).contains("[doc-redis] Redis 扩容指南");
            assertThat(block).contains("扩容步骤：1.添加节点 2.执行rebalance");
            assertThat(block).contains("(score=0.8500)");
        }

        @Test
        @DisplayName("多条命中 → 多条知识库片段")
        void shouldRenderMultipleHits() {
            RagContextBuilder builder = new RagContextBuilder(null);
            List<RagHit> hits = List.of(
                    new RagHit(chunk(1L, "doc-1", "标题1", "内容1"), 0.9, 0.08, 0.85),
                    new RagHit(chunk(2L, "doc-2", "标题2", "内容2"), 0.8, 0.05, 0.73));

            String block = builder.buildContextBlock(hits);

            assertThat(block).contains("[doc-1] 标题1");
            assertThat(block).contains("[doc-2] 标题2");
            assertThat(block).contains("(score=0.7300)");
        }

        @Test
        @DisplayName("标题为 null → 显示空标题")
        void shouldHandleNullTitle() {
            RagContextBuilder builder = new RagContextBuilder(null);
            RagHit hit = new RagHit(chunk(1L, "src", null, "content"), 0.9, 0.1, 0.8);

            String block = builder.buildContextBlock(List.of(hit));

            assertThat(block).contains("[src] ");
        }

        @Test
        @DisplayName("sourceId 为 null → 显示空来源")
        void shouldHandleNullSourceId() {
            RagContextBuilder builder = new RagContextBuilder(null);
            RagHit hit = new RagHit(chunk(1L, null, "title", "content"), 0.9, 0.1, 0.8);

            String block = builder.buildContextBlock(List.of(hit));

            assertThat(block).contains("[] title");
        }
    }

    private static KnowledgeChunk chunk(Long id, String sourceId, String title, String content) {
        return new KnowledgeChunk(id, 100L, sourceId, title, content, 50, null);
    }
}
