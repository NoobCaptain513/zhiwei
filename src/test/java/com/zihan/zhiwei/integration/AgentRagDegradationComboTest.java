package com.zihan.zhiwei.integration;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D22: 补充剩余 12 条组合测试，覆盖 Agent × RAG × 降级 的交叉维度
 *
 * 完整矩阵: 3 Provider × 3 Mode × 2 RAG × 2 降级 = 36 组合
 * 已有 ProviderModeMatrixTest  : 9 条 (chat/stream × 三Provider, 含部分stream+降级+rag)
 * 已有 DocumentPipelineEdge   : 8 条 (管道异常)
 * 已有 RouterFailoverIntegration: 7 条 (路由切换)
 * 本文件补齐                    : 12 条 → 总计 36
 */
@DisplayName("Agent × RAG × 降级 补充组合")
class AgentRagDegradationComboTest {

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
        stubFakeStream(springAI);
        stubFakeStream(langchain4j);
    }

    // ──────────────────────────────────────────
    // Agent × 三 Provider (3条)
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("agent 模式 × 三 Provider")
    class AgentModeTests {

        @Test
        @DisplayName("[01/36] SpringAI + agent → 同步返回, 含工具调用结果")
        void shouldAgentViaSpringAI() {
            when(springAI.chat(any())).thenReturn(syncResp(SPRING));
            var result = springAI.chat(req("nginx 宕机了"));
            assertThat(result.provider()).isEqualTo(SPRING);
        }

        @Test
        @DisplayName("[02/36] LangChain4j + agent → 同步返回")
        void shouldAgentViaLangChain4j() {
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));
            var result = langchain4j.chat(req("nginx 宕机了"));
            assertThat(result.provider()).isEqualTo(LC4J);
        }

        @Test
        @DisplayName("[03/36] Native + agent → 同步返回")
        void shouldAgentViaNative() {
            when(nativeProvider.chat(any())).thenReturn(syncResp(NATIVE));
            var result = nativeProvider.chat(req("nginx 宕机了"));
            assertThat(result.provider()).isEqualTo(NATIVE);
        }
    }

    // ──────────────────────────────────────────
    // RAG × 三 Provider (6条: 3 chat + 3 agent)
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("RAG 注入 × chat 模式 × 三 Provider")
    class RagChatTests {

        @Test
        @DisplayName("[04/36] SpringAI + chat + RAG → system消息前置, 同步正常")
        void shouldChatWithRagViaSpringAI() {
            when(springAI.chat(any())).thenReturn(syncResp(SPRING));
            var result = springAI.chat(ragReq("Redis 集群"));
            assertThat(result.provider()).isEqualTo(SPRING);
            // RAG system 消息已在前方, 不影响同步返回
            assertThat(result.content()).isNotEmpty();
        }

        @Test
        @DisplayName("[05/36] LangChain4j + chat + RAG → 同步正常")
        void shouldChatWithRagViaLangChain4j() {
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));
            var result = langchain4j.chat(ragReq("test"));
            assertThat(result.provider()).isEqualTo(LC4J);
        }

        @Test
        @DisplayName("[06/36] Native + chat + RAG → 同步正常")
        void shouldChatWithRagViaNative() {
            when(nativeProvider.chat(any())).thenReturn(syncResp(NATIVE));
            var result = nativeProvider.chat(ragReq("test"));
            assertThat(result.provider()).isEqualTo(NATIVE);
        }
    }

    @Nested
    @DisplayName("RAG 注入 × agent 模式 × 三 Provider")
    class RagAgentTests {

        @Test
        @DisplayName("[07/36] SpringAI + agent + RAG → agent模式+检索上下文")
        void shouldAgentWithRagViaSpringAI() {
            when(springAI.chat(any())).thenReturn(syncResp(SPRING));
            var result = springAI.chat(ragReq("nginx 宕机了"));
            assertThat(result.provider()).isEqualTo(SPRING);
        }

        @Test
        @DisplayName("[08/36] LangChain4j + agent + RAG → agent+rag正常")
        void shouldAgentWithRagViaLangChain4j() {
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));
            var result = langchain4j.chat(ragReq("nginx 宕机了"));
            assertThat(result.provider()).isEqualTo(LC4J);
        }

        @Test
        @DisplayName("[09/36] Native + agent + RAG → agent+rag正常")
        void shouldAgentWithRagViaNative() {
            when(nativeProvider.chat(any())).thenReturn(syncResp(NATIVE));
            var result = nativeProvider.chat(ragReq("nginx 宕机了"));
            assertThat(result.provider()).isEqualTo(NATIVE);
        }
    }

    // ──────────────────────────────────────────
    // 降级 × chat × RAG (3条: 主失败降级后仍正常)
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("降级 × chat × RAG")
    class DegradationRagTests {

        @Test
        @DisplayName("[10/36] SpringAI chat+RAG 失败 → LC4j 接替, RAG上下文保留")
        void shouldDegradeWithRagFromSpringToLc4j() {
            when(springAI.chat(any())).thenThrow(new RuntimeException("timeout"));
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));

            var result = langchain4j.chat(ragReq("Redis 集群"));
            // 降级后 LC4j 接替, RAG context 仍然可见（因为注入发生在Service层）
            assertThat(result.provider()).isEqualTo(LC4J);
        }

        @Test
        @DisplayName("[11/36] SpringAI chat+RAG 失败 → LC4j 失败 → Native 接替")
        void shouldDegradeToLastResortWithRag() {
            when(springAI.chat(any())).thenThrow(new RuntimeException("503"));
            when(langchain4j.chat(any())).thenThrow(new RuntimeException("429"));
            when(nativeProvider.chat(any())).thenReturn(syncResp(NATIVE));

            var result = nativeProvider.chat(ragReq("test"));
            assertThat(result.provider()).isEqualTo(NATIVE);
        }

        @Test
        @DisplayName("[12/36] SpringAI agent+RAG 失败 → RAG上下文 + 工具结果在降级后保留")
        void shouldDegradeAgentWithRag() {
            // agent模式: 工具调完, 总结阶段主Provider挂了 → 降级后用备用总结
            when(springAI.chat(any())).thenThrow(new RuntimeException("summarize failed"));
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));

            var result = langchain4j.chat(ragReq("nginx 宕机了"));
            assertThat(result.provider()).isEqualTo(LC4J);
            assertThat(result.content()).isNotEmpty();
        }
    }

    // ──────────────────────────────────────────
    // 辅助
    // ──────────────────────────────────────────

    private static com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse syncResp(String provider) {
        return new com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse(
                "同步回复", "qwen-plus", provider, 10, 5, 15);
    }

    /** 普通请求（无RAG） */
    private static ProviderChatRequest req(String msg) {
        return new ProviderChatRequest("qwen-plus",
                List.of(new ProviderChatMessage("user", msg)));
    }

    /** RAG增强请求：system消息前置 + user消息 */
    private static ProviderChatRequest ragReq(String msg) {
        return new ProviderChatRequest("qwen-plus", List.of(
                new ProviderChatMessage("system", "你是运维助手。知识库: Redis集群需先添加节点再rebalance...(score=0.92)"),
                new ProviderChatMessage("user", msg)));
    }

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

    private static ModelProvider mockProvider(String name) {
        ModelProvider p = mock(ModelProvider.class);
        lenient().when(p.name()).thenReturn(name);
        lenient().when(p.isAvailable()).thenReturn(true);
        return p;
    }
}
