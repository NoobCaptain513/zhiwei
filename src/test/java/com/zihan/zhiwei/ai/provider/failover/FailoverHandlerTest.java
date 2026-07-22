package com.zihan.zhiwei.ai.provider.failover;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.ProviderMetrics;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.health.FailoverEventLog;
import com.zihan.zhiwei.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FailoverHandler 故障降级测试")
class FailoverHandlerTest {

    @Mock private ModelProvider springAiProvider;
    @Mock private ModelProvider langchain4jProvider;
    @Mock private ModelProvider nativeProvider;
    @Mock private ProviderMetrics providerMetrics;
    @Mock private FailoverEventLog failoverEventLog;

    private CircuitBreakerRegistry cbRegistry;
    private MockEnvironment environment;
    private ProviderChatRequest request;

    private static final String PRIMARY = "spring-ai-alibaba";
    private static final String FALLBACK_1 = "langchain4j-openai";
    private static final String FALLBACK_2 = "native-dashscope";

    @BeforeEach
    void setUp() {
        lenient().when(springAiProvider.name()).thenReturn(PRIMARY);
        lenient().when(springAiProvider.isAvailable()).thenReturn(true);
        lenient().when(langchain4jProvider.name()).thenReturn(FALLBACK_1);
        lenient().when(langchain4jProvider.isAvailable()).thenReturn(true);
        lenient().when(nativeProvider.name()).thenReturn(FALLBACK_2);
        lenient().when(nativeProvider.isAvailable()).thenReturn(true);

        cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());

        environment = new MockEnvironment()
                .withProperty("zhiwei.ai.router.failover-chain[0]", PRIMARY)
                .withProperty("zhiwei.ai.router.failover-chain[1]", FALLBACK_1)
                .withProperty("zhiwei.ai.router.failover-chain[2]", FALLBACK_2);

        request = new ProviderChatRequest("qwen-plus", List.of(
                new ProviderChatMessage("user", "你好")));
    }

    private FailoverHandler handler(boolean retryEnabled, int maxAttempts) {
        List<ModelProvider> providers = List.of(springAiProvider, langchain4jProvider, nativeProvider);
        return new FailoverHandler(providers, cbRegistry, providerMetrics, environment, retryEnabled, maxAttempts, failoverEventLog);
    }

    // ──────────────────────────────────────────
    // 第一部分：降级链基本行为（重试关闭，测试降级逻辑）
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("降级链基本行为")
    class BasicFallbackTests {

        @Test
        @DisplayName("主 Provider 正常 → 不降级")
        void shouldUsePrimaryWhenHealthy() {
            when(springAiProvider.chat(any())).thenReturn(mockResponse(PRIMARY));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isFalse();
            assertThat(result.actualProvider()).isEqualTo(PRIMARY);
            assertThat(result.response().provider()).isEqualTo(PRIMARY);
            assertThat(result.events()).isEmpty();
            verify(springAiProvider).chat(any());
            verify(langchain4jProvider, never()).chat(any());
            verify(nativeProvider, never()).chat(any());
        }

        @Test
        @DisplayName("主 Provider 抛异常 → 降级到 LangChain4j")
        void shouldFallbackToLangchain4jWhenPrimaryFails() {
            when(springAiProvider.chat(any())).thenThrow(new RuntimeException("API timeout"));
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(FALLBACK_1);
            assertThat(result.events()).hasSize(1);
            assertThat(result.events().get(0).fromProvider()).isEqualTo(PRIMARY);
            assertThat(result.events().get(0).toProvider()).isEqualTo(FALLBACK_1);
            assertThat(result.events().get(0).reason()).contains("API timeout");
            verify(springAiProvider).chat(any());
            verify(langchain4jProvider).chat(any());
            verify(nativeProvider, never()).chat(any());
        }

        @Test
        @DisplayName("前两个 Provider 均失败 → 降级到 Native（最后防线）")
        void shouldFallbackToNativeWhenBothFail() {
            when(springAiProvider.chat(any())).thenThrow(new RuntimeException("503"));
            when(langchain4jProvider.chat(any())).thenThrow(new RuntimeException("429"));
            when(nativeProvider.chat(any())).thenReturn(mockResponse(FALLBACK_2));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(FALLBACK_2);
            assertThat(result.events()).hasSize(2);
        }

        @Test
        @DisplayName("全部 Provider 失败 → 抛出 BusinessException")
        void shouldThrowWhenAllProvidersFail() {
            when(springAiProvider.chat(any())).thenThrow(new RuntimeException("fail1"));
            when(langchain4jProvider.chat(any())).thenThrow(new RuntimeException("fail2"));
            when(nativeProvider.chat(any())).thenThrow(new RuntimeException("fail3"));
            FailoverHandler h = handler(false, 0);

            assertThatThrownBy(() -> h.execute(PRIMARY, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("全部 Provider 调用失败");
        }

        @Test
        @DisplayName("未知主 Provider → 降级链第一个可用 Provider 接管")
        void shouldFallbackToFirstAvailableWhenPrimaryUnknown() {
            when(springAiProvider.chat(any())).thenReturn(mockResponse(PRIMARY));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute("unknown-provider", request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(PRIMARY);
            assertThat(result.primaryProvider()).isEqualTo("unknown-provider");
        }

        @Test
        @DisplayName("Primary 为空 → 使用降级链第一个")
        void shouldUseChainFirstWhenPrimaryNull() {
            when(springAiProvider.chat(any())).thenReturn(mockResponse(PRIMARY));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(null, request);

            assertThat(result.actualProvider()).isEqualTo(PRIMARY);
        }

        @Test
        @DisplayName("指定 FALLBACK_1 为主 Provider → 正常调用不降级")
        void shouldUseExplicitPrimary() {
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(FALLBACK_1, request);

            assertThat(result.degraded()).isFalse();
            assertThat(result.actualProvider()).isEqualTo(FALLBACK_1);
            verify(springAiProvider, never()).chat(any());
        }
    }

    // ──────────────────────────────────────────
    // 第二部分：幂等重试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("幂等重试")
    class RetryTests {

        @Test
        @DisplayName("主 Provider 失败 → 重试 1 次 → 失败 → 降级到备用")
        void shouldRetryOnceThenFallback() {
            when(springAiProvider.chat(any()))
                    .thenThrow(new RuntimeException("first"))
                    .thenThrow(new RuntimeException("retry"));
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(true, 1);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(FALLBACK_1);
            verify(springAiProvider, times(2)).chat(any());
            verify(langchain4jProvider, times(1)).chat(any());
        }

        @Test
        @DisplayName("主 Provider 失败 → 重试成功 → 不降级")
        void shouldNotFallbackWhenRetrySucceeds() {
            when(springAiProvider.chat(any()))
                    .thenThrow(new RuntimeException("transient"))
                    .thenReturn(mockResponse(PRIMARY));
            FailoverHandler h = handler(true, 1);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isFalse();
            assertThat(result.actualProvider()).isEqualTo(PRIMARY);
            verify(springAiProvider, times(2)).chat(any());
            verify(langchain4jProvider, never()).chat(any());
        }

        @Test
        @DisplayName("重试禁用 → 失败直接降级")
        void shouldNotRetryWhenDisabled() {
            when(springAiProvider.chat(any())).thenThrow(new RuntimeException("error"));
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(false, 0);

            h.execute(PRIMARY, request);

            verify(springAiProvider, times(1)).chat(any());
            verify(langchain4jProvider, times(1)).chat(any());
        }
    }

    // ──────────────────────────────────────────
    // 第三部分：熔断器跳过
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("熔断器 OPEN 跳过")
    class CircuitBreakerTests {

        @Test
        @DisplayName("熔断器 OPEN → 跳过主 Provider，走降级链")
        void shouldSkipProviderWhenCircuitOpen() {
            cbRegistry.circuitBreaker(PRIMARY).transitionToOpenState();
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(FALLBACK_1);
            assertThat(result.events()).hasSize(1);
            assertThat(result.events().get(0).reason()).isEqualTo("CIRCUIT_OPEN");
            verify(springAiProvider, never()).chat(any());
        }

        @Test
        @DisplayName("两个 Provider 熔断器 OPEN → 直接到 Native")
        void shouldSkipMultipleOpenCircuits() {
            cbRegistry.circuitBreaker(PRIMARY).transitionToOpenState();
            cbRegistry.circuitBreaker(FALLBACK_1).transitionToOpenState();
            when(nativeProvider.chat(any())).thenReturn(mockResponse(FALLBACK_2));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(FALLBACK_2);
            assertThat(result.events()).hasSize(2);
            verify(springAiProvider, never()).chat(any());
            verify(langchain4jProvider, never()).chat(any());
        }

        @Test
        @DisplayName("isCircuitOpen 正确反映熔断器状态")
        void shouldCheckCircuitState() {
            cbRegistry.circuitBreaker(PRIMARY).transitionToOpenState();

            assertThat(handler(false, 0).isCircuitOpen(PRIMARY)).isTrue();
            assertThat(handler(false, 0).isCircuitOpen(FALLBACK_1)).isFalse();
        }

        @Test
        @DisplayName("CallNotPermittedException 被正确处理为跳过低级")
        void shouldHandleCallNotPermitted() {
            // 打满失败触发熔断 OPEN
            CircuitBreaker cb = cbRegistry.circuitBreaker(PRIMARY);
            for (int i = 0; i < 20; i++) {
                try {
                    cb.executeSupplier(() -> {
                        throw new RuntimeException("forced");
                    });
                } catch (Exception ignored) {
                }
            }
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    // ──────────────────────────────────────────
    // 第四部分：降级事件记录
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("降级事件记录")
    class DegradationEventTests {

        @Test
        @DisplayName("降级切换生成 FailoverEvent，含 from/to/reason/time")
        void shouldRecordFullEventOnDegradation() {
            when(springAiProvider.chat(any())).thenThrow(new RuntimeException("timeout"));
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.events()).hasSize(1);
            FailoverEvent event = result.events().get(0);
            assertThat(event.fromProvider()).isEqualTo(PRIMARY);
            assertThat(event.toProvider()).isEqualTo(FALLBACK_1);
            assertThat(event.reason()).contains("timeout");
            assertThat(event.occurredAt()).isNotNull();
        }

        @Test
        @DisplayName("无降级时 events 为空")
        void shouldHaveNoEventsWithoutDegradation() {
            when(springAiProvider.chat(any())).thenReturn(mockResponse(PRIMARY));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isFalse();
            assertThat(result.events()).isEmpty();
        }

        @Test
        @DisplayName("两次降级 → events 含两笔记录")
        void shouldHaveTwoEventsForTwoDegradations() {
            when(springAiProvider.chat(any())).thenThrow(new RuntimeException("503"));
            when(langchain4jProvider.chat(any())).thenThrow(new RuntimeException("429"));
            when(nativeProvider.chat(any())).thenReturn(mockResponse(FALLBACK_2));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.events()).hasSize(2);
            assertThat(result.events().get(0).fromProvider()).isEqualTo(PRIMARY);
            assertThat(result.events().get(1).fromProvider()).isEqualTo(FALLBACK_1);
        }
    }

    // ──────────────────────────────────────────
    // 第五部分：边界情况
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("Provider 不可用时跳过")
        void shouldSkipUnavailableProvider() {
            when(springAiProvider.isAvailable()).thenReturn(false);
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(FALLBACK_1);
            verify(springAiProvider, never()).chat(any());
        }

        @Test
        @DisplayName("Provider 在容器中不存在 → 跳过")
        void shouldSkipProviderNotInMap() {
            when(springAiProvider.chat(any())).thenReturn(mockResponse(PRIMARY));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute("ghost-provider", request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(PRIMARY);
        }

        @Test
        @DisplayName("异常消息为 null → reason 使用异常类名")
        void shouldUseClassNameForNullMessage() {
            RuntimeException nullMsgEx = new RuntimeException((String) null);
            when(springAiProvider.chat(any())).thenThrow(nullMsgEx);
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.events()).hasSize(1);
            assertThat(result.events().get(0).reason()).contains("RuntimeException");
        }

        @Test
        @DisplayName("degraded 字段正确标记是否发生降级")
        void shouldSetDegradedFlagCorrectly() {
            when(springAiProvider.chat(any())).thenThrow(new RuntimeException("fail"));
            when(langchain4jProvider.chat(any())).thenReturn(mockResponse(FALLBACK_1));
            FailoverHandler h = handler(false, 0);

            FailoverResult result = h.execute(PRIMARY, request);

            assertThat(result.degraded()).isTrue();
            assertThat(result.primaryProvider()).isEqualTo(PRIMARY);
            assertThat(result.actualProvider()).isEqualTo(FALLBACK_1);
            assertThat(result.response()).isNotNull();
            assertThat(result.response().content()).isEqualTo("AI reply");
        }
    }

    private static ProviderChatResponse mockResponse(String provider) {
        return new ProviderChatResponse("AI reply", "qwen-plus", provider, 100, 50, 150);
    }
}
