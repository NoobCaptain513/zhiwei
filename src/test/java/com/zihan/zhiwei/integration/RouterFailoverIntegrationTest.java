package com.zihan.zhiwei.integration;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.ProviderMetrics;
import com.zihan.zhiwei.ai.provider.HealthMonitor;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverHandler;
import com.zihan.zhiwei.ai.provider.health.FailoverEventLog;
import com.zihan.zhiwei.ai.provider.nativehttp.CostCalibrationInterceptor;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D22: 路由切换集成测试（手动降 Provider 验证路由器自动切换）
 *
 * 测试三个层面：
 * 1. 同步模式：手动让 springAI 失败 → 自动降级到 langchain4j
 * 2. 熔断器：打满失败触发 OPEN → 后续请求直接跳过 springAI
 * 3. 恢复：熔断器 HALF_OPEN → 探测成功 → 回 CLOSED
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("路由切换集成测试")
class RouterFailoverIntegrationTest {

    @Mock private ProviderMetrics providerMetrics;
    @Mock private HealthMonitor healthMonitor;
    @Mock private CostCalibrationInterceptor costCalibrationInterceptor;
    @Mock private FailoverEventLog failoverEventLog;

    private ModelProvider springAI;
    private ModelProvider langchain4j;
    private ModelProvider nativeProvider;

    private CircuitBreakerRegistry cbRegistry;
    private FailoverHandler failoverHandler;
    private ModelProviderRouter router;

    private static final String SPRING = "spring-ai-alibaba";
    private static final String LC4J = "langchain4j-openai";
    private static final String NATIVE = "native-dashscope";

    @BeforeEach
    void setUp() {
        springAI = mockProvider(SPRING);
        langchain4j = mockProvider(LC4J);
        nativeProvider = mockProvider(NATIVE);

        // fake流式: 内部调 chat() → 全文一次发出
        stubFakeStream(springAI);
        stubFakeStream(langchain4j);
        // native保持默认(可直接mock streamChat做真流式)

        lenient().when(healthMonitor.isHealthy(anyString())).thenReturn(true);
        lenient().when(providerMetrics.snapshot(anyString()))
                .thenReturn(new ProviderMetrics.Snapshot("test", 10, 10, 0, 1.0, 50, 40));
        lenient().when(costCalibrationInterceptor.readWeight(anyString())).thenReturn(1.0);

        cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofMillis(500))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());

        MockEnvironment env = new MockEnvironment()
                .withProperty("zhiwei.ai.router.failover-chain[0]", SPRING)
                .withProperty("zhiwei.ai.router.failover-chain[1]", LC4J)
                .withProperty("zhiwei.ai.router.failover-chain[2]", NATIVE);

        failoverHandler = new FailoverHandler(
                List.of(springAI, langchain4j, nativeProvider),
                cbRegistry, providerMetrics, env, true, 1, failoverEventLog);

        router = new ModelProviderRouter(
                List.of(springAI, langchain4j, nativeProvider),
                providerMetrics, failoverHandler, healthMonitor, costCalibrationInterceptor);
    }

    // ──────────────────────────────────────────
    // 同步降级
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("同步模式降级")
    class SyncDegradationTests {

        @Test
        @DisplayName("手动关 springAI → 自动降级到 langchain4j")
        void shouldAutoFallbackWhenPrimaryFails() {
            when(springAI.chat(any())).thenThrow(new RuntimeException("503 Service Unavailable"));
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));

            var result = router.executeWithFailover(SPRING, req("test"));

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(LC4J);
            // 运行时降级事件被记录
            assertThat(result.events()).isNotEmpty();
        }

        @Test
        @DisplayName("两个都关 → 自动降到 Native（最后防线）")
        void shouldFallbackToLastResort() {
            when(springAI.chat(any())).thenThrow(new RuntimeException("503"));
            when(langchain4j.chat(any())).thenThrow(new RuntimeException("429"));
            when(nativeProvider.chat(any())).thenReturn(syncResp(NATIVE));

            var result = router.executeWithFailover(SPRING, req("test"));

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(NATIVE);
            // 两次降级事件
            assertThat(result.events()).hasSize(2);
        }
    }

    // ──────────────────────────────────────────
    // 熔断器状态转换
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("熔断器自动切换")
    class CircuitBreakerTests {

        @Test
        @DisplayName("springAI 连续失败 → 熔断器 OPEN → 后续请求自动跳过")
        void shouldOpenCircuitAndAutoSkip() throws Exception {
            CircuitBreaker cb = cbRegistry.circuitBreaker(SPRING);

            // 打满失败触发 OPEN
            fillWithFailures(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // 后续请求：springAI 被跳过，直接走 langchain4j
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));
            var result = router.executeWithFailover(SPRING, req("test"));

            assertThat(result.degraded()).isTrue();
            assertThat(result.actualProvider()).isEqualTo(LC4J);
            // springAI 从未被调用（熔断器直接拒绝）
            verify(springAI, never()).chat(any());
        }

        @Test
        @DisplayName("OPEN → 等500ms → HALF_OPEN → 探测成功 → CLOSED")
        void shouldRecoverFromOpenState() throws Exception {
            CircuitBreaker cb = cbRegistry.circuitBreaker(SPRING);

            // 打满失败 → OPEN
            fillWithFailures(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // 等 600ms → HALF_OPEN
            Thread.sleep(600);
            cb.transitionToHalfOpenState();
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            // 探测成功 → CLOSED
            when(springAI.chat(any())).thenReturn(syncResp(SPRING));
            var result = router.executeWithFailover(SPRING, req("test"));

            assertThat(result.degraded()).isFalse();
            assertThat(result.actualProvider()).isEqualTo(SPRING);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("HALF_OPEN 探测失败 → 回到 OPEN")
        void shouldReopenOnFailedProbe() throws Exception {
            CircuitBreaker cb = cbRegistry.circuitBreaker(SPRING);
            fillWithFailures(cb);
            Thread.sleep(600);
            cb.transitionToHalfOpenState();

            // 探测也失败
            when(springAI.chat(any())).thenThrow(new RuntimeException("probe failed"));
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));

            router.executeWithFailover(SPRING, req("test"));

            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    // ──────────────────────────────────────────
    // 流式 + 降级组合
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("流式 + 降级组合")
    class StreamDegradationTests {

        @Test
        @DisplayName("流式: 主 Provider 启动就失败 → 自动降级, 从备用开始流式")
        void shouldDegradeStreamBeforeAnyToken() {
            // springAI 直接失败
            when(springAI.chat(any())).thenThrow(new RuntimeException("connect refused"));
            // langchain4j 接替（它的 streamChat 默认 fallback 到 chat）
            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));

            List<String> tokens = new ArrayList<>();
            var result = router.streamChatWithFailover(SPRING, req("hi"), tokens::add);

            assertThat(tokens).isNotEmpty();
            assertThat(result.provider()).isEqualTo(LC4J);
        }

        @Test
        @DisplayName("熔断器 OPEN + 流式 → 直接走备用 Provider")
        void shouldSkipOpenCircuitInStream() throws Exception {
            CircuitBreaker cb = cbRegistry.circuitBreaker(SPRING);
            fillWithFailures(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            when(langchain4j.chat(any())).thenReturn(syncResp(LC4J));

            List<String> tokens = new ArrayList<>();
            var result = router.streamChatWithFailover(SPRING, req("hi"), tokens::add);

            assertThat(tokens).isNotEmpty();
            assertThat(result.provider()).isEqualTo(LC4J);
        }
    }

    // ──────────────────────────────────────────
    // 辅助
    // ──────────────────────────────────────────

    private static ProviderChatResponse syncResp(String provider) {
        return new ProviderChatResponse(
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

    @SuppressWarnings("unchecked")
    private static void stubFakeStream(ModelProvider p) {
        lenient().when(p.streamChat(any(), any())).thenAnswer(inv -> {
            var req = (ProviderChatRequest) inv.getArgument(0);
            java.util.function.Consumer<String> cb = inv.getArgument(1);
            var resp = p.chat(req);
            if (resp != null && resp.content() != null && !resp.content().isBlank()) {
                cb.accept(resp.content());
            }
            return com.zihan.zhiwei.ai.stream.StreamResult.of(
                    resp.model(), p.name(), resp.promptTokens(), resp.completionTokens());
        });
    }

    private static void fillWithFailures(CircuitBreaker cb) {
        for (int i = 0; i < 20; i++) {
            cb.onError(100, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new RuntimeException("error-" + i));
        }
    }
}
