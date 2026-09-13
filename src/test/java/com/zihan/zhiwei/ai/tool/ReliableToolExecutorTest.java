package com.zihan.zhiwei.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.agent.runtime.AgentNodeObserver;
import com.zihan.zhiwei.service.IdempotentRequestCache;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReliableToolExecutorTest {

    private ReliableToolExecutor executor;

    @AfterEach
    void close() {
        if (executor != null) executor.close();
    }

    @Test
    void shouldRetryReadOnlyTransientFailure() {
        OpsAgentToolService delegate = mock(OpsAgentToolService.class);
        AtomicInteger attempts = new AtomicInteger();
        when(delegate.executeRaw(eq("queryMetrics"), anyMap())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) throw new RuntimeException("temporary");
            return ToolCallResult.builder().toolName("queryMetrics").success(true).data("{}").build();
        });
        executor = executor(delegate, 500, 1, 2);

        ToolCallResult result = executor.execute("u1", null, "queryMetrics", Map.of("service", "api"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(2);
        verify(delegate, times(2)).executeRaw(eq("queryMetrics"), anyMap());
    }

    @Test
    void shouldTimeoutSlowTool() {
        OpsAgentToolService delegate = mock(OpsAgentToolService.class);
        when(delegate.executeRaw(eq("searchLogs"), anyMap())).thenAnswer(invocation -> {
            Thread.sleep(500);
            return ToolCallResult.builder().toolName("searchLogs").success(true).data("{}").build();
        });
        executor = executor(delegate, 30, 0, 2);

        ToolCallResult result = executor.execute("u1", null, "searchLogs", Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(ToolCallStatus.TIMEOUT);
    }

    @Test
    void shouldRunReadOnlyToolsInParallelAndKeepOrder() {
        OpsAgentToolService delegate = mock(OpsAgentToolService.class);
        when(delegate.executeRaw(anyString(), anyMap())).thenAnswer(invocation -> {
            Thread.sleep(120);
            String name = invocation.getArgument(0);
            return ToolCallResult.builder().toolName(name).success(true).data(name).build();
        });
        executor = executor(delegate, 500, 0, 2);
        long start = System.nanoTime();

        List<ToolCallResult> results = executor.executeReadOnlyBatch("u1", List.of(
                new ToolInvocation("queryServerStatus", Map.of()),
                new ToolInvocation("queryMetrics", Map.of())));

        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isLessThan(220);
        assertThat(results).extracting(ToolCallResult::getToolName)
                .containsExactly("queryServerStatus", "queryMetrics");
    }

    @Test
    void shouldRequireApprovalAndDeduplicateSideEffect() {
        OpsAgentToolService delegate = mock(OpsAgentToolService.class);
        when(delegate.executeRaw(eq("createTicket"), anyMap()))
                .thenReturn(ToolCallResult.builder().toolName("createTicket").success(true).data("{\"ticketId\":\"T1\"}").build());
        IdempotentRequestCache cache = mock(IdempotentRequestCache.class);
        when(cache.fingerprint(anyString(), any())).thenReturn("fp");
        when(cache.resolve(anyString(), anyString(), anyString(), eq(ToolCallResult.class), anyString()))
                .thenReturn(Optional.empty());
        when(cache.acquire(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(IdempotentRequestCache.IdempotencyLease.acquired("tool:createTicket", "u1", "approval", "owner"));
        ToolApprovalService approvals = new ToolApprovalService(Duration.ofMinutes(5));
        executor = executor(delegate, cache, approvals, 500, 1, 2);
        Map<String, Object> params = Map.of("title", "告警", "description", "磁盘满");

        ToolCallResult pending = executor.execute("u1", null, "createTicket", params);
        ToolCallResult executed = executor.execute("u1", pending.getApprovalId(), "createTicket", params);

        assertThat(pending.getStatus()).isEqualTo(ToolCallStatus.APPROVAL_REQUIRED);
        assertThat(executed.isSuccess()).isTrue();
        verify(delegate, times(1)).executeRaw(eq("createTicket"), anyMap());
        verify(cache).remember(any(), eq("fp"), same(executed));
    }

    @Test
    void shouldOpenCircuitAfterRepeatedFailures() {
        OpsAgentToolService delegate = mock(OpsAgentToolService.class);
        when(delegate.executeRaw(eq("queryMetrics"), anyMap())).thenThrow(new RuntimeException("down"));
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50).build();
        executor = new ReliableToolExecutor(delegate, new ToolApprovalService(Duration.ofMinutes(5)),
                mock(IdempotentRequestCache.class), CircuitBreakerRegistry.of(config),
                new AgentNodeObserver(new SimpleMeterRegistry()), 500, 0, 2);

        executor.execute("u1", null, "queryMetrics", Map.of());
        executor.execute("u1", null, "queryMetrics", Map.of());
        ToolCallResult rejected = executor.execute("u1", null, "queryMetrics", Map.of());

        assertThat(rejected.getStatus()).isEqualTo(ToolCallStatus.CIRCUIT_OPEN);
        verify(delegate, times(2)).executeRaw(eq("queryMetrics"), anyMap());
    }

    @Test
    void shouldRejectWhenToolBulkheadIsFull() throws Exception {
        OpsAgentToolService delegate = mock(OpsAgentToolService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.executeRaw(eq("searchLogs"), anyMap())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return ToolCallResult.builder().toolName("searchLogs").success(true).data("{}").build();
        });
        executor = executor(delegate, 1_000, 0, 1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> executor.execute("u1", null, "searchLogs", Map.of()));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            ToolCallResult rejected = executor.execute("u2", null, "searchLogs", Map.of());
            release.countDown();

            assertThat(rejected.getStatus()).isEqualTo(ToolCallStatus.BULKHEAD_FULL);
            assertThat(first.get(2, TimeUnit.SECONDS).isSuccess()).isTrue();
        }
    }

    private ReliableToolExecutor executor(OpsAgentToolService delegate, long timeoutMs, int retries, int bulkhead) {
        IdempotentRequestCache cache = mock(IdempotentRequestCache.class);
        return executor(delegate, cache, new ToolApprovalService(Duration.ofMinutes(5)), timeoutMs, retries, bulkhead);
    }

    private ReliableToolExecutor executor(OpsAgentToolService delegate, IdempotentRequestCache cache,
                                          ToolApprovalService approvals, long timeoutMs, int retries, int bulkhead) {
        return new ReliableToolExecutor(delegate, approvals, cache, CircuitBreakerRegistry.ofDefaults(),
                new AgentNodeObserver(new SimpleMeterRegistry()), timeoutMs, retries, bulkhead);
    }
}
