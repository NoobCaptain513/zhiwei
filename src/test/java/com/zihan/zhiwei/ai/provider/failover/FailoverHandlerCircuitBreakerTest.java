package com.zihan.zhiwei.ai.provider.failover;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Resilience4j CircuitBreaker 状态机转换测试")
class FailoverHandlerCircuitBreakerTest {

    private static CircuitBreakerConfig createTestConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .permittedNumberOfCallsInHalfOpenState(1)
                .waitDurationInOpenState(Duration.ofMillis(500))
                .build();
    }

    // ──────────────────────────────────────────
    // 状态转换：CLOSED → OPEN → HALF_OPEN → CLOSED
    // ──────────────────────────────────────────

    @Test
    @DisplayName("CLOSED → OPEN：连续失败达阈值后熔断打开")
    void shouldOpenCircuitAfterFailures() {
        CircuitBreaker cb = CircuitBreakerRegistry.of(createTestConfig())
                .circuitBreaker("test-provider");

        IntStream.range(0, 3).forEach(i ->
                cb.onSuccess(100, TimeUnit.MILLISECONDS));

        IntStream.range(0, 7).forEach(i ->
                cb.onError(100, TimeUnit.MILLISECONDS, new RuntimeException("error")));

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("OPEN → HALF_OPEN → CLOSED：探测成功则恢复")
    void shouldCloseCircuitOnProbeSuccess() throws Exception {
        CircuitBreaker cb = CircuitBreakerRegistry.of(createTestConfig())
                .circuitBreaker("test-provider");

        fillWithFailures(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(600);

        cb.transitionToHalfOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        cb.onSuccess(100, TimeUnit.MILLISECONDS);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("OPEN → HALF_OPEN → OPEN：探测失败重新熔断")
    void shouldReopenCircuitOnProbeFailure() throws Exception {
        CircuitBreaker cb = CircuitBreakerRegistry.of(createTestConfig())
                .circuitBreaker("test-provider");

        fillWithFailures(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(600);

        cb.transitionToHalfOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        cb.onError(100, TimeUnit.MILLISECONDS, new RuntimeException("probe failed"));
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("完整状态流转：CLOSED → OPEN → HALF_OPEN → CLOSED")
    void shouldCompleteFullStateCycle() throws Exception {
        CircuitBreaker cb = CircuitBreakerRegistry.of(createTestConfig())
                .circuitBreaker("test-provider");

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        fillWithFailures(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(600);
        cb.transitionToHalfOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        cb.onSuccess(100, TimeUnit.MILLISECONDS);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ──────────────────────────────────────────
    // OPEN 状态下的行为
    // ──────────────────────────────────────────

    @Test
    @DisplayName("OPEN 状态拒绝调用，抛出 CallNotPermittedException")
    void shouldThrowCallNotPermittedWhenOpen() {
        CircuitBreaker cb = CircuitBreakerRegistry.of(createTestConfig())
                .circuitBreaker("test-provider");

        fillWithFailures(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThrows(
                io.github.resilience4j.circuitbreaker.CallNotPermittedException.class,
                () -> cb.executeSupplier(() -> "should-not-run"));
    }

    @Test
    @DisplayName("CLOSED 状态正常执行 Supplier")
    void shouldExecuteSupplierWhenClosed() {
        CircuitBreaker cb = CircuitBreakerRegistry.of(createTestConfig())
                .circuitBreaker("test-provider");

        String result = cb.executeSupplier(() -> "success");

        assertThat(result).isEqualTo("success");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ──────────────────────────────────────────
    // 多 Provider 独立熔断
    // ──────────────────────────────────────────

    @Test
    @DisplayName("不同 Provider 的熔断器互相独立")
    void shouldIsolateCircuitBreakersByProvider() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(createTestConfig());

        CircuitBreaker cbA = registry.circuitBreaker("spring-ai-alibaba");
        CircuitBreaker cbB = registry.circuitBreaker("langchain4j-openai");

        fillWithFailures(cbA);

        assertThat(cbA.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cbB.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private static void fillWithFailures(CircuitBreaker cb) {
        IntStream.range(0, 20).forEach(i ->
                cb.onError(100, TimeUnit.MILLISECONDS, new RuntimeException("error-" + i)));
    }
}
