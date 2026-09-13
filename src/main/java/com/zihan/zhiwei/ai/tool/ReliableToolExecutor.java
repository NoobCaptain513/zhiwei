package com.zihan.zhiwei.ai.tool;

import com.zihan.zhiwei.ai.agent.runtime.AgentNodeObserver;
import com.zihan.zhiwei.service.IdempotentRequestCache;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Reliability boundary for tools: timeout, retry, circuit breaker, bulkhead, approval and idempotency. */
@Service
@ConditionalOnBean(OpsAgentToolService.class)
public class ReliableToolExecutor implements AutoCloseable {

    private static final String SIDE_EFFECT_TOOL = "createTicket";

    private final OpsAgentToolService delegate;
    private final ToolApprovalService approvals;
    private final IdempotentRequestCache idempotency;
    private final CircuitBreakerRegistry circuitBreakers;
    private final AgentNodeObserver observer;
    private final long timeoutMs;
    private final int readOnlyRetries;
    private final int bulkheadSize;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

    @Autowired
    public ReliableToolExecutor(
            OpsAgentToolService delegate,
            ToolApprovalService approvals,
            IdempotentRequestCache idempotency,
            CircuitBreakerRegistry circuitBreakers,
            AgentNodeObserver observer,
            @Value("${zhiwei.ai.agent.reliability.tool.timeout-ms:3000}") long timeoutMs,
            @Value("${zhiwei.ai.agent.reliability.tool.read-only-retries:1}") int readOnlyRetries,
            @Value("${zhiwei.ai.agent.reliability.tool.bulkhead-size:8}") int bulkheadSize) {
        this.delegate = delegate;
        this.approvals = approvals;
        this.idempotency = idempotency;
        this.circuitBreakers = circuitBreakers;
        this.observer = observer;
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.readOnlyRetries = Math.max(0, readOnlyRetries);
        this.bulkheadSize = Math.max(1, bulkheadSize);
    }

    public ToolCallResult execute(String userId, String approvalId, String toolName, Map<String, Object> params) {
        Map<String, Object> safeParams = params == null ? Map.of() : Map.copyOf(params);
        if (isSideEffect(toolName)) {
            return executeSideEffect(userId, approvalId, toolName, safeParams);
        }
        return executeReliable(toolName, safeParams, true);
    }

    public List<ToolCallResult> executeReadOnlyBatch(String userId, List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        List<Future<ToolCallResult>> futures = new ArrayList<>(invocations.size());
        for (ToolInvocation invocation : invocations) {
            if (isSideEffect(invocation.toolName())) {
                throw new IllegalArgumentException("side-effect tool cannot run in read-only batch: " + invocation.toolName());
            }
            futures.add(executor.submit(() -> execute(userId, null, invocation.toolName(), invocation.params())));
        }
        List<ToolCallResult> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
            } catch (Exception e) {
                String name = invocations.get(i).toolName();
                results.add(failed(name, ToolCallStatus.FAILED, 0, 0L, rootMessage(e), null));
            }
        }
        return List.copyOf(results);
    }

    private ToolCallResult executeSideEffect(
            String userId, String approvalId, String toolName, Map<String, Object> params) {
        if (approvalId == null || approvalId.isBlank()) {
            String issued = approvals.issue(userId, toolName, params);
            return failed(toolName, ToolCallStatus.APPROVAL_REQUIRED, 0, 0L,
                    "工具有副作用，需要用户审批后执行", issued);
        }
        if (!approvals.validate(approvalId, userId, toolName, params)) {
            return failed(toolName, ToolCallStatus.APPROVAL_REJECTED, 0, 0L,
                    "审批已过期、用户不匹配或参数已变更", approvalId);
        }

        String namespace = "tool:" + toolName;
        String fingerprint = idempotency.fingerprint(namespace, params);
        Optional<ToolCallResult> cached = idempotency.resolve(
                namespace, userId, approvalId, ToolCallResult.class, fingerprint);
        if (cached.isPresent()) {
            return cached.get();
        }
        IdempotentRequestCache.IdempotencyLease lease = idempotency.acquire(
                namespace, userId, approvalId, fingerprint, Math.max(1, (int) (timeoutMs / 1000) + 5));
        if (!lease.acquired()) {
            Optional<ToolCallResult> completed = idempotency.resolve(
                    namespace, userId, approvalId, ToolCallResult.class, fingerprint);
            return completed.orElseGet(() -> failed(toolName, ToolCallStatus.IN_PROGRESS, 0, 0L,
                    "同一审批正在执行", approvalId));
        }

        ToolCallResult result = executeReliable(toolName, params, false);
        result.setApprovalId(approvalId);
        // Store every terminal result. A timeout has an unknown outcome and must never trigger an automatic replay.
        idempotency.remember(lease, fingerprint, result);
        return result;
    }

    private ToolCallResult executeReliable(String toolName, Map<String, Object> params, boolean retryable) {
        long started = System.nanoTime();
        int attempts = 1 + (retryable ? readOnlyRetries : 0);
        ToolCallStatus lastStatus = ToolCallStatus.FAILED;
        String lastError = "tool execution failed";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ToolCallResult result = observer.observe("tool." + toolName,
                        () -> invokeOnce(toolName, params));
                result.setStatus(ToolCallStatus.SUCCESS);
                result.setAttempts(attempt);
                result.setLatencyMs(elapsedMs(started));
                return result;
            } catch (ToolTimeoutException e) {
                lastStatus = ToolCallStatus.TIMEOUT;
                lastError = e.getMessage();
            } catch (BulkheadFullException e) {
                lastStatus = ToolCallStatus.BULKHEAD_FULL;
                lastError = e.getMessage();
                break;
            } catch (CallNotPermittedException e) {
                lastStatus = ToolCallStatus.CIRCUIT_OPEN;
                lastError = e.getMessage();
                break;
            } catch (NonRetryableToolException e) {
                lastStatus = ToolCallStatus.FAILED;
                lastError = e.getMessage();
                break;
            } catch (RuntimeException e) {
                lastStatus = ToolCallStatus.FAILED;
                lastError = rootMessage(e);
            }
            if (!retryable || attempt == attempts) {
                return failed(toolName, lastStatus, attempt, elapsedMs(started), lastError, null);
            }
        }
        return failed(toolName, lastStatus, attempts, elapsedMs(started), lastError, null);
    }

    private ToolCallResult invokeOnce(String toolName, Map<String, Object> params) {
        Semaphore bulkhead = bulkheads.computeIfAbsent(toolName, ignored -> new Semaphore(bulkheadSize));
        if (!bulkhead.tryAcquire()) {
            throw new BulkheadFullException("tool bulkhead full: " + toolName);
        }
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker("tool." + toolName);
        Future<ToolCallResult> future = executor.submit(() -> {
            try {
                return circuitBreaker.executeSupplier(() -> {
                    ToolCallResult result = delegate.executeRaw(toolName, params);
                    if (result == null || !result.isSuccess()) {
                        throw new NonRetryableToolException(result == null ? "tool returned null" : result.getError());
                    }
                    return result;
                });
            } finally {
                bulkhead.release();
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ToolTimeoutException("tool timed out after " + timeoutMs + "ms: " + toolName);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolTimeoutException("tool execution interrupted: " + toolName);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        }
    }

    private static ToolCallResult failed(String toolName, ToolCallStatus status, int attempts,
                                         long latencyMs, String error, String approvalId) {
        return ToolCallResult.builder()
                .toolName(toolName)
                .success(false)
                .status(status)
                .attempts(attempts)
                .latencyMs(latencyMs)
                .error(error)
                .approvalId(approvalId)
                .build();
    }

    private static boolean isSideEffect(String toolName) {
        return SIDE_EFFECT_TOOL.equals(toolName);
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private static final class ToolTimeoutException extends RuntimeException {
        private ToolTimeoutException(String message) { super(message); }
    }

    private static final class BulkheadFullException extends RuntimeException {
        private BulkheadFullException(String message) { super(message); }
    }

    private static final class NonRetryableToolException extends RuntimeException {
        private NonRetryableToolException(String message) { super(message); }
    }
}
