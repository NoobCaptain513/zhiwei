package com.zihan.zhiwei.ai.stream;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.ProviderMetrics;
import com.zihan.zhiwei.ai.provider.HealthMonitor;
import com.zihan.zhiwei.ai.provider.nativehttp.CostCalibrationInterceptor;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SSE 流式传输组测试")
class AiStreamTests {

    @Mock private ProviderMetrics providerMetrics;
    @Mock private HealthMonitor healthMonitor;
    @Mock private CostCalibrationInterceptor costCalibrationInterceptor;

    @BeforeEach
    void setUp() {
        lenient().when(healthMonitor.isHealthy(anyString())).thenReturn(true);
        lenient().when(providerMetrics.snapshot(anyString()))
                .thenReturn(new ProviderMetrics.Snapshot("test", 10, 10, 0, 1.0, 50, 40));
        lenient().when(costCalibrationInterceptor.readWeight(anyString())).thenReturn(1.0);
    }

    // ──────────────────────────────────────────
    // AiStreamAdvice 单元测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("AiStreamAdvice SSE 工具方法")
    class StreamAdviceTests {

        private final AiStreamAdvice advice = new AiStreamAdvice();

        @Test
        @DisplayName("jsonEscape 普通字符串 → 加双引号包裹")
        void shouldEscapeNormalString() {
            assertThat(AiStreamAdvice.jsonEscape("hello")).isEqualTo("\"hello\"");
        }

        @Test
        @DisplayName("jsonEscape null → 返回空引号")
        void shouldEscapeNull() {
            assertThat(AiStreamAdvice.jsonEscape(null)).isEqualTo("\"\"");
        }

        @Test
        @DisplayName("jsonEscape 含换行 → 转义 \\n")
        void shouldEscapeNewline() {
            assertThat(AiStreamAdvice.jsonEscape("line1\nline2")).isEqualTo("\"line1\\nline2\"");
        }

        @Test
        @DisplayName("jsonEscape 含双引号 → 转义 \\\"")
        void shouldEscapeDoubleQuote() {
            assertThat(AiStreamAdvice.jsonEscape("he\"llo")).isEqualTo("\"he\\\"llo\"");
        }

        @Test
        @DisplayName("jsonEscape 含反斜杠 → 转义 \\\\")
        void shouldEscapeBackslash() {
            assertThat(AiStreamAdvice.jsonEscape("a\\b")).isEqualTo("\"a\\\\b\"");
        }

        @Test
        @DisplayName("jsonEscape 含制表符 → 转义 \\t")
        void shouldEscapeTab() {
            assertThat(AiStreamAdvice.jsonEscape("a\tb")).isEqualTo("\"a\\tb\"");
        }

        @Test
        @DisplayName("jsonEscape 含回车 → 转义 \\r")
        void shouldEscapeCarriageReturn() {
            assertThat(AiStreamAdvice.jsonEscape("a\rb")).isEqualTo("\"a\\rb\"");
        }

        @Test
        @DisplayName("sendToken 格式正确")
        void shouldFormatSendToken() {
            String result = AiStreamAdvice.jsonEscape("Hello");
            assertThat(result).isEqualTo("\"Hello\"");
        }

        @Test
        @DisplayName("StreamResult 包含所有字段")
        void shouldHaveCorrectFields() {
            StreamResult result = new StreamResult("qwen-plus", "native-dashscope", 100, 50, 150);

            assertThat(result.model()).isEqualTo("qwen-plus");
            assertThat(result.provider()).isEqualTo("native-dashscope");
            assertThat(result.promptTokens()).isEqualTo(100);
            assertThat(result.completionTokens()).isEqualTo(50);
            assertThat(result.totalTokens()).isEqualTo(150);
        }
    }

    // ──────────────────────────────────────────
    // StreamResult 单元测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("StreamResult 元数据")
    class StreamResultTests {

        @Test
        @DisplayName("of() 自动计算 totalTokens = prompt + completion")
        void shouldAutoCalcTotalTokens() {
            StreamResult result = StreamResult.of("qwen-plus", "native", 100, 50);

            assertThat(result.totalTokens()).isEqualTo(150);
            assertThat(result.model()).isEqualTo("qwen-plus");
            assertThat(result.provider()).isEqualTo("native");
            assertThat(result.promptTokens()).isEqualTo(100);
            assertThat(result.completionTokens()).isEqualTo(50);
        }
    }

    // ──────────────────────────────────────────
    // ModelProvider streamChat 默认行为
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("ModelProvider streamChat 接口")
    class ModelProviderStreamTests {

        @Test
        @DisplayName("未重写 streamChat → fallback 同步,一次性发完全文")
        void shouldFallbackToSyncWhenNotOverridden() {
            ProviderChatResponse syncResponse = new ProviderChatResponse(
                    "完整回复", "qwen-plus", "test", 50, 30, 80);

            ModelProvider provider = new ModelProvider() {
                @Override public String name() { return "test"; }
                @Override public ProviderChatResponse chat(ProviderChatRequest r) { return syncResponse; }
            };

            List<String> tokens = new ArrayList<>();
            StreamResult result = provider.streamChat(
                    new ProviderChatRequest("qwen-plus", List.of(
                            new ProviderChatMessage("user", "hi"))),
                    tokens::add);

            assertThat(tokens).containsExactly("完整回复");
            assertThat(result.totalTokens()).isEqualTo(80);
        }
    }

    // ──────────────────────────────────────────
    // ModelProviderRouter 流式路由
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("ModelProviderRouter 流式路由")
    class StreamRoutingTests {


        @Test
        @DisplayName("成功 → 逐个 token 发出, 返回 StreamResult")
        void shouldStreamSuccessfully() {
            ModelProvider primary = mockProvider("p1", true);
            when(primary.streamChat(any(), any())).thenAnswer(inv -> {
                Consumer<String> cb = inv.getArgument(1);
                cb.accept("A");
                cb.accept("B");
                cb.accept("C");
                return StreamResult.of("m", "p1", 10, 5);
            });

            ModelProviderRouter router = buildRouter(List.of(primary));
            List<String> tokens = new ArrayList<>();
            StreamResult result = router.streamChatWithFailover(
                    new ProviderChatRequest("m", List.of()), tokens::add);

            assertThat(tokens).containsExactly("A", "B", "C");
            assertThat(result.totalTokens()).isEqualTo(15);
        }

        @Test
        @DisplayName("第一个失败 → 降级到第二个")
        void shouldFallbackToNextWhenFirstFails() {
            ModelProvider p1 = mockProvider("p1", true);
            ModelProvider p2 = mockProvider("p2", true);
            when(p1.streamChat(any(), any())).thenThrow(new RuntimeException("fail"));
            when(p2.streamChat(any(), any())).thenAnswer(inv -> {
                Consumer<String> cb = inv.getArgument(1);
                cb.accept("from-p2");
                return StreamResult.of("m", "p2", 5, 3);
            });

            ModelProviderRouter router = buildRouter(List.of(p1, p2));
            List<String> tokens = new ArrayList<>();
            StreamResult result = router.streamChatWithFailover(
                    new ProviderChatRequest("m", List.of()), tokens::add);

            assertThat(tokens).containsExactly("from-p2");
            assertThat(result.provider()).isEqualTo("p2");
        }

        @Test
        @DisplayName("已发 token → 后续失败不降级, 直接抛异常")
        void shouldNotFallbackAfterTokenSent() {
            ModelProvider p1 = mockProvider("p1", true);
            when(p1.streamChat(any(), any())).thenAnswer(inv -> {
                Consumer<String> cb = inv.getArgument(1);
                cb.accept("partial-1");
                cb.accept("partial-2");
                throw new RuntimeException("connection lost");
            });

            ModelProviderRouter router = buildRouter(List.of(p1));
            List<String> tokens = new ArrayList<>();

            assertThatThrownBy(() -> router.streamChatWithFailover(
                    new ProviderChatRequest("m", List.of()), tokens::add))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("流式传输中断");

            assertThat(tokens).containsExactly("partial-1", "partial-2");
        }

        @Test
        @DisplayName("无可用 Provider → 抛异常")
        void shouldThrowWhenNoProviderAvailable() {
            ModelProviderRouter router = buildRouter(List.of());

            assertThatThrownBy(() -> router.streamChatWithFailover(
                    new ProviderChatRequest("m", List.of()), t -> {}))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("没有可用的 Provider");
        }

        @Test
        @DisplayName("空排名仍尝试默认 Provider")
        void shouldTryDefaultWhenRankedEmpty() {
            ModelProvider fallback = mockProvider("fallback", false);
            when(fallback.streamChat(any(), any())).thenAnswer(inv -> {
                Consumer<String> cb = inv.getArgument(1);
                cb.accept("fallback-token");
                return StreamResult.of("m", "fallback", 1, 1);
            });

            ModelProviderRouter router = buildRouter(List.of(fallback));
            List<String> tokens = new ArrayList<>();
            StreamResult result = router.streamChatWithFailover(
                    "fallback", new ProviderChatRequest("m", List.of()), tokens::add);

            assertThat(tokens).containsExactly("fallback-token");
        }
    }

    // ──────────────────────────────────────────
    // 事件类型验证
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("SSE 事件类型")
    class EventTypeTests {

        @Test
        @DisplayName("token 事件 → {\"token\":\"...\"}")
        void shouldHaveTokenEventFormat() {
            String token = "Hello";
            String expected = "{\"token\":" + AiStreamAdvice.jsonEscape(token) + "}";

            assertThat(expected).isEqualTo("{\"token\":\"Hello\"}");
        }

        @Test
        @DisplayName("done 事件 → 含 model/provider/totalTokens")
        void shouldHaveDoneEventFormat() {
            String json = String.format(
                    "{\"done\":true,\"model\":\"%s\",\"provider\":\"%s\",\"totalTokens\":%d}",
                    "qwen-plus", "native-dashscope", 150);

            assertThat(json).contains("\"done\":true");
            assertThat(json).contains("\"model\":\"qwen-plus\"");
            assertThat(json).contains("\"provider\":\"native-dashscope\"");
            assertThat(json).contains("\"totalTokens\":150");
        }

        @Test
        @DisplayName("error 事件 → 含 error 字段")
        void shouldHaveErrorEventFormat() {
            String error = "{\"error\":" + AiStreamAdvice.jsonEscape("timeout") + "}";

            assertThat(error).isEqualTo("{\"error\":\"timeout\"}");
        }
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private static ModelProvider mockProvider(String name, boolean available) {
        ModelProvider p = mock(ModelProvider.class);
        lenient().when(p.name()).thenReturn(name);
        lenient().when(p.isAvailable()).thenReturn(available);
        return p;
    }

    private ModelProviderRouter buildRouter(List<ModelProvider> providers) {
        return new ModelProviderRouter(
                providers,
                providerMetrics,
                null,   // FailoverHandler
                healthMonitor,
                costCalibrationInterceptor
        );
    }
}
