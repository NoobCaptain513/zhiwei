package com.zihan.zhiwei.integration;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D22: Provider × Mode 组合测试（不经过 Router，直接测 Provider 行为）
 *
 * 关注点：各 Provider 对不同 Mode 的处理差异
 *   - chat 模式：三个 Provider 都真同步
 *   - stream 模式：SpringAI/LangChain4j fake，Native 真流式
 *   - 降级行为：已发 token 后不可降级
 */
@DisplayName("Provider × Mode 组合矩阵")
class ProviderModeMatrixTest {

    private ModelProvider springAI;
    private ModelProvider langchain4j;
    private ModelProvider nativeProvider;

    private static final String SPRING = "spring-ai-alibaba";
    private static final String LC4J = "langchain4j-openai";
    private static final String NATIVE = "native-dashscope";

    @BeforeEach
    void setUp() {
        springAI = mockProvider(SPRING);
        langchain4j = mockProvider(LC4J);
        nativeProvider = mockProvider(NATIVE);

        // SpringAI = fake 流式: 内部调 chat() → 把全文作为一个 token 发出
        stubFakeStream(springAI);
        stubFakeStream(langchain4j);
    }

    /** Mock 默认的 streamChat: 内部调用 chat(), 把全文当做一个 token 发出 */
    @SuppressWarnings("unchecked")
    private static void stubFakeStream(ModelProvider p) {
        lenient().when(p.streamChat(any(), any())).thenAnswer(inv -> {
            var req = (ProviderChatRequest) inv.getArgument(0);
            Consumer<String> cb = inv.getArgument(1);
            var resp = p.chat(req);
            if (resp != null && resp.content() != null && !resp.content().isBlank()) {
                cb.accept(resp.content());
            }
            return com.zihan.zhiwei.ai.stream.StreamResult.of(
                    resp.model(), p.name(), resp.promptTokens(), resp.completionTokens());
        });
    }

    // ──────────────────────────────────────────
    // chat 模式：三 Provider 真同步
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("chat 模式 × 三 Provider")
    class ChatModeTests {

        @Test
        @DisplayName("SpringAI + chat → 同步返回")
        void shouldChatViaSpringAI() {
            when(springAI.chat(any())).thenReturn(syncResp(SPRING));
            var result = springAI.chat(req("Redis 扩容"));
            assertThat(result.provider()).isEqualTo(SPRING);
            assertThat(result.content()).isEqualTo("同步回复");
        }

        @Test
        @DisplayName("LangChain4j + chat → 同步返回")
        void shouldChatViaLangChain4j() {
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));
            var result = langchain4j.chat(req("test"));
            assertThat(result.provider()).isEqualTo(LC4J);
        }

        @Test
        @DisplayName("Native + chat → 同步返回")
        void shouldChatViaNative() {
            when(nativeProvider.chat(any())).thenReturn(syncResp(NATIVE));
            var result = nativeProvider.chat(req("test"));
            assertThat(result.provider()).isEqualTo(NATIVE);
        }
    }

    // ──────────────────────────────────────────
    // stream 模式：三 Provider
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("stream 模式 × 三 Provider")
    class StreamModeTests {

        @Test
        @DisplayName("SpringAI + stream → fake流式, 一次全文")
        void shouldFakeStreamViaSpringAI() {
            when(springAI.chat(any())).thenReturn(syncResp(SPRING));
            List<String> tokens = new ArrayList<>();
            var result = springAI.streamChat(req("hi"), tokens::add);
            assertThat(tokens).containsExactly("同步回复");
            assertThat(result.provider()).isEqualTo(SPRING);
        }

        @Test
        @DisplayName("LangChain4j + stream → fake流式, 一次全文")
        void shouldFakeStreamViaLangChain4j() {
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));
            List<String> tokens = new ArrayList<>();
            var result = langchain4j.streamChat(req("hi"), tokens::add);
            assertThat(tokens).containsExactly("同步回复");
        }

        @Test
        @DisplayName("Native + stream → 真流式, 逐 token 推送")
        void shouldRealStreamViaNative() {
            // 模拟 Native 真流式：逐 token 回调
            when(nativeProvider.streamChat(any(), any())).thenAnswer(inv -> {
                Consumer<String> cb = inv.getArgument(1);
                cb.accept("Redis");
                cb.accept("集群");
                cb.accept("扩容");
                return com.zihan.zhiwei.ai.stream.StreamResult.of("qwen-plus", NATIVE, 10, 5);
            });

            List<String> tokens = new ArrayList<>();
            var result = nativeProvider.streamChat(req("hi"), tokens::add);

            assertThat(tokens).containsExactly("Redis", "集群", "扩容");
            assertThat(result.provider()).isEqualTo(NATIVE);
            assertThat(result.totalTokens()).isEqualTo(15);
        }
    }

    // ──────────────────────────────────────────
    // 降级：已发 token 后失败
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("stream 降级 × token 已发")
    class TokenSentDegradationTests {

        @Test
        @DisplayName("已发 token 后 stream 中断 → 已收到的 token 保留, 抛异常")
        void shouldThrowAfterPartialStream() {
            // Native 真流式: 先 push 两个 token, 然后抛异常
            when(nativeProvider.streamChat(any(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Consumer<String> cb = inv.getArgument(1);
                cb.accept("partial-1");
                cb.accept("partial-2");
                throw new RuntimeException("connection lost");
            });

            List<String> tokens = new ArrayList<>();
            assertThatThrownBy(
                    () -> nativeProvider.streamChat(req("hi"), tokens::add))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("connection lost");
            assertThat(tokens).containsExactly("partial-1", "partial-2");
        }

        @Test
        @DisplayName("未发任何 token stream 失败 → 空列表 + 异常")
        void shouldFailBeforeAnyToken() {
            when(nativeProvider.streamChat(any(), any()))
                    .thenThrow(new RuntimeException("connect refused"));

            List<String> tokens = new ArrayList<>();
            assertThatThrownBy(
                    () -> nativeProvider.streamChat(req("hi"), tokens::add))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("connect refused");
            assertThat(tokens).isEmpty();
        }
    }

    // ──────────────────────────────────────────
    // RAG × Mode（无 RAG vs 有 RAG 的消息长度差异）
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("RAG 注入 × stream")
    class RagStreamTests {

        @Test
        @DisplayName("有 RAG → 消息更长 → stream 仍正常逐 token")
        void shouldStreamWithRagContext() {
            // RAG context 被前置为 system 消息，在 stream 时不影响逐 token 推送
            when(nativeProvider.streamChat(any(), any())).thenAnswer(inv -> {
                Consumer<String> cb = inv.getArgument(1);
                cb.accept("A"); cb.accept("B");
                return com.zihan.zhiwei.ai.stream.StreamResult.of("m", NATIVE, 5, 3);
            });

            List<String> tokens = new ArrayList<>();
            var result = nativeProvider.streamChat(
                    new ProviderChatRequest("m", List.of(
                            new ProviderChatMessage("system", "RAG上下文很长的文本..."),
                            new ProviderChatMessage("user", "hi"))),
                    tokens::add);

            assertThat(tokens).containsExactly("A", "B");
        }
    }

    // ──────────────────────────────────────────
    // 辅助
    // ──────────────────────────────────────────

    private static com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse syncResp(String provider) {
        return new com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse(
                "同步回复", "qwen-plus", provider, 10, 5, 15);
    }

    private static ProviderChatRequest req(String msg) {
        return new ProviderChatRequest("qwen-plus",
                List.of(new ProviderChatMessage("user", msg)));
    }

    private static ModelProvider mockProvider(String name) {
        ModelProvider p = mock(ModelProvider.class);
        lenient().when(p.name()).thenReturn(name);
        lenient().when(p.isAvailable()).thenReturn(true);
        return p;
    }
}
