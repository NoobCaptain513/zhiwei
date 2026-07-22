package com.zihan.zhiwei.ai.provider.failover;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.ProviderMetrics;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.health.FailoverEventLog;
import com.zihan.zhiwei.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * D8: 故障降级
 * - 每个 Provider 一把 CircuitBreaker（CLOSED / OPEN / HALF_OPEN）
 * - 降级链：spring-ai-alibaba → langchain4j-openai → native-dashscope
 * - chat 幂等：同一 Provider 失败后再重试 1 次；切换 Provider 不算重试次数
 */
@Slf4j
@Component
public class FailoverHandler {

    private static final List<String> DEFAULT_FAILOVER_CHAIN = List.of(
            "spring-ai-alibaba",
            "langchain4j-openai",
            "native-dashscope"
    );

    private final Map<String, ModelProvider> providerMap;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ProviderMetrics providerMetrics;
    private final List<String> failoverChain;
    private final boolean retryEnabled;
    private final int maxAttempts;
    private final FailoverEventLog failoverEventLog;

    public FailoverHandler(
            List<ModelProvider> providers,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ProviderMetrics providerMetrics,
            Environment environment,
            @Value("${zhiwei.ai.router.retry.enabled:true}") boolean retryEnabled,
            @Value("${zhiwei.ai.router.retry.max-attempts:1}") int maxAttempts,
            FailoverEventLog failoverEventLog) {
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(ModelProvider::name, Function.identity(), (a, b) -> a));
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.providerMetrics = providerMetrics;
        // YAML 列表绑定为 failover-chain[0]/[1]...，不能用 @Value("${...failover-chain}")
        this.failoverChain = Binder.get(environment)
                .bind("zhiwei.ai.router.failover-chain", Bindable.listOf(String.class))
                .orElse(DEFAULT_FAILOVER_CHAIN);
        this.retryEnabled = retryEnabled;
        this.maxAttempts = Math.max(0, maxAttempts);
        log.info("[Failover] chain={}", this.failoverChain);
        this.failoverEventLog = failoverEventLog;
    }

    public FailoverResult execute(String primaryProvider, ProviderChatRequest request) {
        List<String> chain = buildChain(primaryProvider);
        List<FailoverEvent> events = new ArrayList<>();
        Exception lastError = null;

        for (int i = 0; i < chain.size(); i++) {
            String name = chain.get(i);
            ModelProvider provider = providerMap.get(name);
            if (provider == null || !provider.isAvailable()) {
                log.warn("[Failover] skip unavailable provider={}", name);
                continue;
            }

            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
            if (cb.getState() == CircuitBreaker.State.OPEN) {
                log.warn("[Failover] circuit OPEN, skip provider={}", name);
                if (i + 1 < chain.size()) {
                    events.add(FailoverEvent.of(name, chain.get(i + 1), "CIRCUIT_OPEN"));
                }
                continue;
            }

            int attempts = 1 + (retryEnabled ? maxAttempts : 0);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                long start = System.currentTimeMillis();
                try {
                    var response = cb.executeSupplier(() -> provider.chat(request));
                    long latency = System.currentTimeMillis() - start;
                    providerMetrics.recordSuccess(name, latency);

                    boolean degraded = !name.equals(primaryProvider);
                    if (degraded) {
                        log.info("[Failover] degraded primary={} actual={} events={}",
                                primaryProvider, name, events.size());
                    }
                    if (degraded && !events.isEmpty()) {
                        for (FailoverEvent evt : events) {
                            failoverEventLog.record(evt);
                        }
                    }
                    return new FailoverResult(response, primaryProvider, name, degraded, latency, List.copyOf(events));
                } catch (CallNotPermittedException e) {
                    long latency = System.currentTimeMillis() - start;
                    providerMetrics.recordFailure(name, latency);
                    lastError = e;
                    log.warn("[Failover] call not permitted provider={} state={}", name, cb.getState());
                    break;
                } catch (Exception e) {
                    long latency = System.currentTimeMillis() - start;
                    providerMetrics.recordFailure(name, latency);
                    lastError = e;
                    log.warn("[Failover] provider={} attempt={}/{} failed: {}",
                            name, attempt, attempts, e.getMessage());

                    if (attempt < attempts) {
                        continue;
                    }
                    if (i + 1 < chain.size()) {
                        events.add(FailoverEvent.of(name, chain.get(i + 1),
                                e.getClass().getSimpleName() + ": " + safeMsg(e)));
                    }
                }
            }
        }

        throw new BusinessException("全部 Provider 调用失败: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    public CircuitBreaker.State stateOf(String provider) {
        return circuitBreakerRegistry.circuitBreaker(provider).getState();
    }

    public boolean isCircuitOpen(String provider) {
        return stateOf(provider) == CircuitBreaker.State.OPEN;
    }

    private List<String> buildChain(String primary) {
        List<String> chain = new ArrayList<>();
        if (primary != null && !primary.isBlank()) {
            chain.add(primary);
        }
        for (String name : failoverChain) {
            if (!chain.contains(name)) {
                chain.add(name);
            }
        }
        return chain;
    }

    private static String safeMsg(Exception e) {
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg.substring(0, Math.min(msg.length(), 120));
    }
}